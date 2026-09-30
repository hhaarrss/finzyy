"""
Router for Transaction management.

Handles transaction creation, filtering, duplicate prevention, and aggregate summaries.
"""

import calendar
import hashlib
import math
from datetime import date, datetime, time, timezone
from typing import Optional, List, Dict
from fastapi import APIRouter, Depends, HTTPException, status, Query, Response
from pydantic import BaseModel
from sqlalchemy import select, and_, or_, func
from sqlalchemy.ext.asyncio import AsyncSession

from database import get_db
from models.transaction import Transaction
from models.user import User
from models.budget import BudgetLimit
from models.merchant_mapping import MerchantMapping
from schemas.transaction import (
    TransactionCreate,
    TransactionUpdate,
    TransactionResponse,
    TransactionSummaryResponse,
    PaginatedTransactionResponse,
    CategorySummaryItem,
    MonthlyCategorySummaryResponse,
    NeedsReviewResponse,
    CategorizeRequest,
    CorrectionRequest,
)
from schemas.sms import SMSIngestionRequest, SMSIngestionResponse
from utils.dependencies import get_current_user
from utils.fingerprint import generate_fingerprint
from utils.categories import CATEGORIES, validate_category_matches_type
from utils.transfer_detector import detect_p2p_transfer
from utils.notifications import check_budget_and_alert
from categorizer.transaction_categorizer import (
    categorize_transaction,
    match_merchant_db,
    merchant_key,
    normalize_category_name,
)
from services.transaction_aggregates import (
    get_category_totals,
    get_mom_change,
    get_budget_utilization,
    get_monthly_overview,
)

router = APIRouter(prefix="/transactions", tags=["Transactions"])




async def find_user_correction(
    db: AsyncSession, user_id: int, merchant_raw: Optional[str]
) -> Optional[MerchantMapping]:
    """
    Layer 1: the signed-in user's own correction for this merchant, if any.

    Always filtered by user_id — one user's correction is never visible to another — and
    keyed with merchant_key(), the same function recategorize uses to write it. The newest
    mapping wins if duplicates exist (the table has no unique constraint).
    """
    key = merchant_key(merchant_raw)
    if not key:
        return None
    result = await db.execute(
        select(MerchantMapping)
        .where(and_(MerchantMapping.user_id == user_id, MerchantMapping.merchant_key == key))
        .order_by(MerchantMapping.last_used_at.desc(), MerchantMapping.id.desc())
        .limit(1)
    )
    return result.scalars().first()


def categorize_parsed_sms(merchant_raw: Optional[str], transaction_type: Optional[str] = None) -> dict:
    """
    Categorize an on-device parsed transaction using its merchant value.
    Fallback/no-confidence matches are routed to Needs Review with review_status='needs_review'.
    """
    enriched = categorize_transaction(merchant_raw, transaction_type)

    category = enriched.get("category") or "Needs Review"
    confidence = enriched.get("confidence") or "none"
    source = enriched.get("source") or "fallback"

    review_status = "auto_categorized"
    if source == "fallback" or confidence in ("none", "low") or category == "Miscellaneous" or category == "Needs Review":
        category = "Needs Review"
        review_status = "needs_review"

    return {
        "category": category,
        "subcategory": enriched.get("subcategory"),
        "merchant": merchant_raw or "Unknown Merchant",
        "merchant_display": enriched.get("merchant_display"),
        "source": source,
        "confidence": confidence,
        "review_status": review_status,
    }


@router.post(
    "/",
    response_model=TransactionResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a new transaction",
)
async def create_transaction(
    tx_in: TransactionCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> Transaction:
    """
    Creates a new transaction for the authenticated user.
    Auto-generates a unique fingerprint and prevents double-posting.

    Args:
        tx_in (TransactionCreate): Transaction data.
        current_user (User): Authenticated user.
        db (AsyncSession): The database session.

    Raises:
        HTTPException: 409 Conflict if a duplicate transaction is detected.

    Returns:
        Transaction: The inserted Transaction database object.
    """
    fingerprint = generate_fingerprint(
        user_id=current_user.id,
        amount=tx_in.amount,
        date_val=tx_in.date,
        account_last4=tx_in.account_last4,
        merchant=tx_in.merchant,
        upi_ref=None,
    )

    # Check for duplicate
    duplicate_query = select(Transaction).where(Transaction.hash_fingerprint == fingerprint)
    result = await db.execute(duplicate_query)
    if result.scalars().first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Transaction already exists (duplicate detected by fingerprint).",
        )

    # Validate category matches transaction type
    if not validate_category_matches_type(tx_in.category, tx_in.type):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Category '{tx_in.category}' is incompatible with transaction type '{tx_in.type}'."
        )

    # P2P Transfer detection
    is_tx_transfer, recipient = detect_p2p_transfer(tx_in.merchant, category=tx_in.category)
    final_cat = "Transfer" if is_tx_transfer else normalize_category_name(tx_in.category)

    # Instantiate model
    new_tx = Transaction(
        user_id=current_user.id,
        amount=tx_in.amount,
        type=tx_in.type.lower(),
        category=final_cat,
        merchant=tx_in.merchant,
        bank=tx_in.bank,
        account_last4=tx_in.account_last4,
        date=tx_in.date,
        hash_fingerprint=fingerprint,
        source=tx_in.source.lower(),
        is_transfer=is_tx_transfer,
        transfer_to=recipient,
        notes=tx_in.notes,
    )

    db.add(new_tx)
    await db.flush()

    if new_tx.type == "debit":
        await check_budget_and_alert(db, current_user.id, new_tx.category, float(new_tx.amount))

    return new_tx


@router.get(
    "/monthly-category-summary",
    response_model=MonthlyCategorySummaryResponse,
    summary="Compute detailed monthly category spending summary with MoM change",
)
async def get_monthly_category_summary(
    month: int = Query(..., ge=1, le=12, description="Target month (1-12)"),
    year: int = Query(..., ge=2020, le=2030, description="Target year (e.g. 2026)"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> MonthlyCategorySummaryResponse:
    """
    Computes a detailed spending breakdown by category for a specific month and year.
    Includes category percentage share, transaction count, top merchant, budget limits,
    budget utilization percent, and month-over-month change.

    Args:
        month (int): Target month (1-12).
        year (int): Target year (2020-2030).
        current_user (User): Authenticated user.
        db (AsyncSession): Database session.

    Returns:
        MonthlyCategorySummaryResponse: Structured monthly spending summary.
    """
    # 1. Target month range
    last_day = calendar.monthrange(year, month)[1]
    start_dt = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    end_dt = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    # 2. Previous month range
    if month == 1:
        prev_month = 12
        prev_year = year - 1
    else:
        prev_month = month - 1
        prev_year = year
    prev_last_day = calendar.monthrange(prev_year, prev_month)[1]
    prev_start_dt = datetime(prev_year, prev_month, 1, 0, 0, 0, tzinfo=timezone.utc)
    prev_end_dt = datetime(prev_year, prev_month, prev_last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    overview = await get_monthly_overview(db, current_user.id, year, month, include_transfers=True)
    budget_statuses = await get_budget_utilization(db, current_user.id, year, month)
    budget_map = {b.category.lower(): b for b in budget_statuses}

    last_day = calendar.monthrange(year, month)[1]
    start_dt = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    end_dt = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    debits_query = select(Transaction).where(
        and_(
            Transaction.user_id == current_user.id,
            Transaction.type == "debit",
            Transaction.date >= start_dt,
            Transaction.date <= end_dt,
        )
    )
    debits_res = await db.execute(debits_query)
    debits = list(debits_res.scalars().all())

    credits_query = select(Transaction).where(
        and_(
            Transaction.user_id == current_user.id,
            Transaction.type == "credit",
            Transaction.date >= start_dt,
            Transaction.date <= end_dt,
            Transaction.is_transfer == True
        )
    )
    credits_res = await db.execute(credits_query)
    transfer_received = round(sum(float(t.amount) for t in credits_res.scalars().all()), 2)

    category_totals = overview["category_totals"]
    total_spent = overview["total_spent"]

    categories_summary = []
    for cat_name, cat_total in category_totals.items():
        percentage = round((cat_total / total_spent) * 100, 1) if total_spent > 0 else 0.0
        cat_txs = [
            t for t in debits
            if normalize_category_name(t.category or "").lower() == cat_name.lower()
        ]
        tx_count = len(cat_txs)

        merchant_totals: Dict[str, float] = {}
        for t in cat_txs:
            shown = t.merchant_display or t.merchant
            if shown and shown.strip():
                m_name = shown.strip()
                merchant_totals[m_name] = merchant_totals.get(m_name, 0.0) + float(t.amount)

        top_merchant = max(merchant_totals.items(), key=lambda x: x[1])[0] if merchant_totals else "N/A"

        b_status = budget_map.get(cat_name.lower())
        b_limit = b_status.limit if b_status else 0.0
        b_used_pct = b_status.percent_used if b_status else 0.0

        categories_summary.append(
            CategorySummaryItem(
                category=cat_name,
                total=cat_total,
                percentage=percentage,
                transaction_count=tx_count,
                top_merchant=top_merchant,
                budget_limit=b_limit,
                budget_used_percent=b_used_pct,
            )
        )

    categories_summary.sort(key=lambda x: x.total, reverse=True)

    if month == 1:
        prev_month = 12
        prev_year = year - 1
    else:
        prev_month = month - 1
        prev_year = year
    prev_last_day = calendar.monthrange(prev_year, prev_month)[1]
    prev_start_dt = datetime(prev_year, prev_month, 1, 0, 0, 0, tzinfo=timezone.utc)
    prev_end_dt = datetime(prev_year, prev_month, prev_last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    prev_query = select(func.coalesce(func.sum(Transaction.amount), 0.0)).where(
        and_(
            Transaction.user_id == current_user.id,
            Transaction.type == "debit",
            Transaction.date >= prev_start_dt,
            Transaction.date <= prev_end_dt,
        )
    )
    prev_res = await db.execute(prev_query)
    previous_month_total = round(float(prev_res.scalar() or 0.0), 2)

    return MonthlyCategorySummaryResponse(
        month=month,
        year=year,
        total_spent=overview["total_spent"],
        merchant_spent=overview["merchant_spent"],
        transfer_sent=overview["transfer_sent"],
        transfer_received=transfer_received,
        categories=categories_summary,
        previous_month_total=previous_month_total,
        month_over_month_change=overview["mom_change_percent"] or 0.0,
    )



@router.get(
    "/",
    response_model=PaginatedTransactionResponse,
    summary="Retrieve paginated and filtered transactions sorted latest first",
)
async def list_transactions(
    page: int = Query(1, ge=1, description="Page number (default 1)"),
    limit: int = Query(10, ge=1, le=50, description="Items per page (default 10, max 50)"),
    month: Optional[int] = Query(None, ge=1, le=12, description="Filter by month (1-12)"),
    year: Optional[int] = Query(None, ge=2020, le=2030, description="Filter by year (e.g. 2026)"),
    start_date: Optional[date] = Query(None, description="Start date filter (YYYY-MM-DD)"),
    end_date: Optional[date] = Query(None, description="End date filter (YYYY-MM-DD)"),
    category: Optional[str] = Query(None, description="Filter transactions by category"),
    type: Optional[str] = Query(None, description="Filter by transaction type ('debit' or 'credit')"),
    review_status: Optional[str] = Query(None, description="Filter by review status ('needs_review', 'reviewed', 'auto_categorized')"),
    include_transfers: bool = Query(True, description="If false, excludes P2P transfer transactions."),
    user_id: Optional[int] = Query(None, description="Filter by user ID (admin/family group access only)"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> PaginatedTransactionResponse:
    """
    Lists and filters transactions belonging to the authenticated user.
    Supports pagination, date range filtering, month/year filtering, and sorting latest first.

    Args:
        page (int): Current page number (1-indexed).
        limit (int): Max items per page (1-50).
        month (Optional[int]): Month filter (1-12).
        year (Optional[int]): Year filter (2020-2030).
        start_date (Optional[date]): Start date filter.
        end_date (Optional[date]): End date filter.
        category (Optional[str]): Category filter.
        type (Optional[str]): Filter by type ('debit' or 'credit').
        review_status (Optional[str]): Review status filter.
        include_transfers (bool): Include or exclude P2P transfers.
        user_id (Optional[int]): Query for another user's transactions (family group only).
        current_user (User): Authenticated user.
        db (AsyncSession): Database session.

    Raises:
        HTTPException: 403 Forbidden if trying to access data of a user outside their family group.

    Returns:
        PaginatedTransactionResponse: Paginated transaction payload with metadata.
    """
    query_target_user_id = current_user.id

    # Reading another user's transactions is not allowed. It used to be allowed for "family
    # members", but anyone could join any family by its number (no invite or approval), which
    # exposed every member's payments. The app never sends user_id.
    if user_id and user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only view your own transactions."
        )

    conditions = [Transaction.user_id == query_target_user_id]

    if not include_transfers:
        conditions.append(Transaction.is_transfer == False)

    # Date range & Month/Year filters
    if month is not None and year is not None:
        last_day = calendar.monthrange(year, month)[1]
        start_of_month = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
        end_of_month = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)
        conditions.append(Transaction.date >= start_of_month)
        conditions.append(Transaction.date <= end_of_month)
    else:
        if start_date:
            start_dt = datetime.combine(start_date, time.min).replace(tzinfo=timezone.utc)
            conditions.append(Transaction.date >= start_dt)
        if end_date:
            end_dt = datetime.combine(end_date, time.max).replace(tzinfo=timezone.utc)
            conditions.append(Transaction.date <= end_dt)

    if category:
        conditions.append(Transaction.category.ilike(category))
    if type:
        conditions.append(Transaction.type == type.lower())
    if review_status:
        conditions.append(Transaction.review_status.ilike(review_status))

    # Total matching count query
    count_query = select(func.count()).select_from(Transaction).where(and_(*conditions))
    count_res = await db.execute(count_query)
    total_count = count_res.scalar() or 0

    total_pages = math.ceil(total_count / limit) if total_count > 0 else 0
    has_more = page < total_pages
    offset = (page - 1) * limit

    # Items query sorted latest first (ORDER BY date DESC, created_at DESC)
    items_query = (
        select(Transaction)
        .where(and_(*conditions))
        .order_by(Transaction.date.desc(), Transaction.created_at.desc())
        .offset(offset)
        .limit(limit)
    )
    items_res = await db.execute(items_query)
    transactions = list(items_res.scalars().all())

    return PaginatedTransactionResponse(
        transactions=transactions,
        total_count=total_count,
        page=page,
        limit=limit,
        has_more=has_more,
        total_pages=total_pages,
    )


@router.get(
    "/summary",
    response_model=Dict[str, float],
    summary="Get category totals for a given month",
)
async def get_summary(
    month: str = Query(..., description="Given month in YYYY-MM format, e.g., 2026-05"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> Dict[str, float]:
    """
    Computes normalized category debit totals for the authenticated user for a given month.
    Delegates to the shared get_category_totals() service layer for consistent normalization,
    placeholder exclusion, and transfer filtering across all pages.

    Args:
        month (str): The month to filter by, formatted as YYYY-MM.
        current_user (User): Authenticated user.
        db (AsyncSession): Database session.

    Raises:
        HTTPException: 400 Bad Request if the month format is invalid.

    Returns:
        Dict[str, float]: Normalized aggregated debit totals grouped by category.
    """
    try:
        parsed_month = datetime.strptime(month, "%Y-%m")
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid month format. Use YYYY-MM format, e.g., 2026-05."
        )

    return await get_category_totals(
        db,
        current_user.id,
        parsed_month.year,
        parsed_month.month,
        include_transfers=False,
    )


@router.post(
    "/ingest-sms",
    response_model=SMSIngestionResponse,
    status_code=status.HTTP_200_OK,
    summary="Ingest an on-device parsed transaction",
)
async def ingest_sms(
    sms_in: SMSIngestionRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> SMSIngestionResponse:
    """
    Stores transaction data parsed on-device.
    Checks for duplicates using SHA-256 hash of (amount + date + account_last4).

    Args:
        sms_in (SMSIngestionRequest): Structured transaction fields from the mobile client.
        current_user (User): Authenticated user.
        db (AsyncSession): Database session.

    Returns:
        SMSIngestionResponse: Ingestion response with success, transaction object, and status message.
    """
    # Layer 1: this user's own correction for this merchant beats every automatic layer.
    # A correction made on a credit must not be applied to a debit (or the reverse): that
    # would fail the type check below and drop the SMS, so it is skipped instead.
    correction = await find_user_correction(db, current_user.id, sms_in.merchant_raw)
    if correction is not None and not validate_category_matches_type(correction.category, sms_in.transaction_type):
        correction = None

    if correction is not None:
        cat_info = {
            "category": correction.category,
            "subcategory": correction.subcategory,
            # This SMS's own merchant text, not the stored display name (which may carry
            # the order number of the transaction that was corrected).
            "merchant": sms_in.merchant_raw or correction.display_name or "Unknown Merchant",
            "merchant_display": (match_merchant_db(sms_in.merchant_raw) or {}).get("merchant_display")
            if sms_in.merchant_raw else None,
            "source": "user_correction",
            "confidence": "high",
            "review_status": "reviewed",
        }
    else:
        # Layers 2-5: categorization engine
        cat_info = categorize_parsed_sms(sms_in.merchant_raw, sms_in.transaction_type)
    category = cat_info["category"]
    # Always the text the bank sent; a recognised brand goes in merchant_display instead.
    merchant = sms_in.merchant_raw or "Unknown Merchant"
    merchant_display = cat_info.get("merchant_display")
    subcategory = cat_info.get("subcategory")
    source = cat_info.get("source") or "sms"
    confidence = cat_info.get("confidence") or "medium"
    review_status = cat_info.get("review_status") or "auto_categorized"

    # Generate the duplicate checker fingerprint
    fingerprint = generate_fingerprint(
        user_id=current_user.id,
        amount=sms_in.amount,
        date_val=sms_in.date,
        account_last4=sms_in.account_last4,
        merchant=sms_in.merchant_raw,
        upi_ref=sms_in.upi_ref,
    )

    # Check for duplicate
    duplicate_query = select(Transaction).where(Transaction.hash_fingerprint == fingerprint)
    result = await db.execute(duplicate_query)
    if result.scalars().first():
        return SMSIngestionResponse(
            success=False,
            transaction=None,
            message="Duplicate transaction detected"
        )

    # Convert date to timezone-aware UTC if timezone-naive
    tx_date = sms_in.date
    if tx_date.tzinfo is None:
        tx_date = tx_date.replace(tzinfo=timezone.utc)

    # P2P Transfer detection — skipped when the user filed this merchant under a real
    # category themselves; their choice is not overridden.
    if correction is not None and category.strip().lower() != "transfer":
        is_tx_transfer, recipient = False, None
    else:
        is_tx_transfer, recipient = detect_p2p_transfer(merchant_display or merchant, category=category)
    if is_tx_transfer:
        category = "Transfer"

    # Validate category matches transaction type
    if not validate_category_matches_type(category, sms_in.transaction_type):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Category '{category}' is incompatible with transaction type '{sms_in.transaction_type}'."
        )

    # Save transaction to database
    new_tx = Transaction(
        user_id=current_user.id,
        amount=sms_in.amount,
        type=sms_in.transaction_type,
        category=normalize_category_name(category),
        subcategory=subcategory,
        merchant=merchant,
        merchant_display=merchant_display,
        upi_ref=sms_in.upi_ref,
        bank=sms_in.bank_sender_id,
        account_last4=sms_in.account_last4,
        date=tx_date,
        hash_fingerprint=fingerprint,
        source=source,
        confidence=confidence,
        review_status=review_status,
        is_transfer=is_tx_transfer,
        transfer_to=recipient,
    )

    db.add(new_tx)
    await db.flush()

    if new_tx.type == "debit":
        await check_budget_and_alert(db, current_user.id, new_tx.category, float(new_tx.amount))

    # Notification extras. The new row is flushed, so both figures already include it.
    visit_count: Optional[int] = None
    if new_tx.type == "debit" and sms_in.merchant_raw:
        visit_count = await db.scalar(
            select(func.count(Transaction.id)).where(
                Transaction.user_id == current_user.id,
                Transaction.type == "debit",
                func.lower(Transaction.merchant) == merchant.lower(),
            )
        )
    # Same call and arguments as the Home screen, so the two numbers always agree.
    now = datetime.now(tz=timezone.utc)
    overview = await get_monthly_overview(db, current_user.id, now.year, now.month, include_transfers=False)

    return SMSIngestionResponse(
        success=True,
        transaction=new_tx,
        message="Transaction ingested successfully",
        merchant_visit_count=visit_count,
        month_spent=float(overview["total_spent"]),
    )


@router.get(
    "/merchants",
    summary="List known merchants for the current user",
)
async def list_known_merchants(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> List[Dict[str, object]]:
    """
    Returns known merchants from learned merchant mappings plus transaction history.
    This exposes existing merchant data for mobile UI; it does not add new aggregation logic.
    """
    merchant_rows: Dict[str, Dict[str, object]] = {}

    mappings_res = await db.execute(
        select(MerchantMapping).where(MerchantMapping.user_id == current_user.id)
    )
    for mapping in mappings_res.scalars().all():
        display = mapping.display_name or mapping.merchant_key
        key = display.strip().lower()
        if not key:
            continue
        merchant_rows[key] = {
            "name": display,
            "category": normalize_category_name(mapping.category),
            "count": int(mapping.count or 0),
        }

    tx_res = await db.execute(
        select(
            func.coalesce(Transaction.merchant_display, Transaction.merchant),
            Transaction.category,
            func.count(Transaction.id),
        )
        .where(
            and_(
                Transaction.user_id == current_user.id,
                func.coalesce(Transaction.merchant_display, Transaction.merchant).is_not(None),
                func.coalesce(Transaction.merchant_display, Transaction.merchant) != "",
            )
        )
        .group_by(func.coalesce(Transaction.merchant_display, Transaction.merchant), Transaction.category)
    )
    for merchant, category, count in tx_res.all():
        display = (merchant or "").strip()
        key = display.lower()
        if not key:
            continue
        existing = merchant_rows.get(key)
        if existing:
            existing["count"] = int(existing.get("count", 0)) + int(count or 0)
            if not existing.get("category"):
                existing["category"] = normalize_category_name(category)
        else:
            merchant_rows[key] = {
                "name": display,
                "category": normalize_category_name(category),
                "count": int(count or 0),
            }

    return sorted(
        merchant_rows.values(),
        key=lambda item: (-int(item.get("count", 0)), str(item.get("name", "")).lower()),
    )

async def record_merchant_correction(
    db: AsyncSession,
    user_id: int,
    merchant_raw: Optional[str],
    category: str,
    subcategory: Optional[str] = None,
    display_name: Optional[str] = None,
) -> None:
    """
    Categoriser Layer 1: remember the user's category for this merchant, keyed exactly the
    way ingest_sms reads it back. Caller commits.
    """
    key = merchant_key(merchant_raw)
    existing_mapping = await find_user_correction(db, user_id, merchant_raw)
    if existing_mapping:
        existing_mapping.category = category
        existing_mapping.subcategory = subcategory
        existing_mapping.display_name = display_name or merchant_raw
        existing_mapping.count += 1
    elif key:
        db.add(MerchantMapping(
            user_id=user_id,
            merchant_key=key,
            category=category,
            subcategory=subcategory,
            display_name=display_name or merchant_raw,
            count=1,
        ))


@router.patch(
    "/{transaction_id}/recategorize",
    status_code=status.HTTP_200_OK,
    summary="Update transaction category and record user feedback correction",
)
async def recategorize_transaction(
    transaction_id: int,
    body: CorrectionRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
) -> dict:
    """
    Allows users to manually correct a transaction's category.
    Updates the record in database and registers feedback correction for future auto-matching.

    Args:
        transaction_id (int): ID of the transaction to correct.
        body (CorrectionRequest): Re-categorization details.
        db (AsyncSession): The database session.
        current_user (User): Authenticated user.

    Raises:
        HTTPException: 404 Not Found if transaction doesn't exist or doesn't belong to current user.

    Returns:
        dict: Success confirmation payload.
    """
    transaction_query = select(Transaction).where(
        and_(
            Transaction.id == transaction_id,
            Transaction.user_id == current_user.id
        )
    )
    res = await db.execute(transaction_query)
    transaction = res.scalars().first()

    if not transaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found"
        )

    transaction.category = normalize_category_name(body.new_category)
    transaction.subcategory = body.subcategory
    transaction.source = "user_correction"
    transaction.confidence = "high"
    transaction.review_status = "reviewed"

    # Save or update MerchantMapping in database (Layer 1).
    await record_merchant_correction(
        db, current_user.id, body.merchant_raw, normalize_category_name(body.new_category),
        body.subcategory, body.display_name,
    )

    await db.commit()

    # Learning is handled directly in database via MerchantMapping table above

    return {
        "transaction_id": transaction_id,
        "category": normalize_category_name(body.new_category),
        "subcategory": body.subcategory,
        "review_status": "reviewed",
        "message": "Category updated and correction saved ✅",
    }


class TransactionUpdateSchema(BaseModel):
    category: Optional[str] = None
    subcategory: Optional[str] = None
    review_status: Optional[str] = None
    merchant: Optional[str] = None


# Only /items/{id}. It was also registered on PATCH /{id}, ahead of edit_transaction, so it
# answered that route and silently dropped amount/date/notes edits from the app.
@router.patch(
    "/items/{transaction_id}",
    response_model=TransactionResponse,
    summary="Update transaction fields (category, review_status, merchant)",
)
async def update_transaction_fields(
    transaction_id: int,
    body: TransactionUpdateSchema,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
) -> Transaction:
    """
    Updates transaction category, review_status, or merchant fields.
    Also trains the categorizer engine with user correction feedback.
    """
    transaction_query = select(Transaction).where(
        and_(
            Transaction.id == transaction_id,
            Transaction.user_id == current_user.id
        )
    )
    res = await db.execute(transaction_query)
    transaction = res.scalars().first()

    if not transaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found"
        )

    if body.category is not None:
        transaction.category = normalize_category_name(body.category)
        transaction.review_status = body.review_status or "reviewed"
        transaction.source = "user_correction"
        transaction.confidence = "high"

        # Save user learning correction. This called an undefined function before and the
        # NameError was swallowed, so nothing was ever learned through this route.
        await record_merchant_correction(
            db, current_user.id, transaction.merchant, transaction.category, body.subcategory,
        )

    if body.subcategory is not None:
        transaction.subcategory = body.subcategory
    if body.review_status is not None:
        transaction.review_status = body.review_status
    if body.merchant is not None:
        transaction.merchant = body.merchant
        transaction.merchant_display = None  # the user's wording replaces any brand match

    await db.commit()
    await db.refresh(transaction)
    return transaction


@router.patch("/{transaction_id}", response_model=TransactionResponse, summary="Edit a transaction")
async def edit_transaction(
    transaction_id: int,
    updates: TransactionUpdate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
) -> Transaction:
    """
    Partially edit an existing transaction owned by the current user.
    Editable fields: category, merchant, amount, date, notes.
    """
    res = await db.execute(select(Transaction).where(Transaction.id == transaction_id))
    transaction = res.scalars().first()

    if not transaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found"
        )

    if transaction.user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not authorized to edit this transaction"
        )

    if updates.merchant is not None:
        transaction.merchant = updates.merchant
        transaction.merchant_display = None  # the user's wording replaces any brand match
    if updates.category is not None:
        transaction.category = normalize_category_name(updates.category)
        transaction.review_status = "reviewed"
        transaction.source = "user_correction"
        transaction.confidence = "high"
        await record_merchant_correction(db, current_user.id, transaction.merchant, transaction.category)
    if updates.amount is not None:
        transaction.amount = updates.amount
    if updates.date is not None:
        transaction.date = updates.date
    if updates.notes is not None:
        transaction.notes = updates.notes

    await db.commit()
    await db.refresh(transaction)
    return transaction


@router.delete("/{transaction_id}", summary="Delete a transaction")
async def delete_transaction(
    transaction_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
) -> dict:
    """
    Hard delete a transaction owned by the current user.
    """
    res = await db.execute(select(Transaction).where(Transaction.id == transaction_id))
    transaction = res.scalars().first()

    if not transaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found"
        )

    if transaction.user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not authorized to delete this transaction"
        )

    await db.delete(transaction)
    await db.commit()
    return {"message": "Transaction deleted successfully"}


@router.get(
    "/needs-review",
    response_model=NeedsReviewResponse,
    summary="Get unreviewed transactions needing user categorization"
)
async def get_needs_review_transactions(
    response: Response,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    Fetch all transactions for current user where category is 'Other'
    or review_status is 'needs_review'. Returns count in X-Needs-Review-Count header.
    """
    query = select(Transaction).where(
        and_(
            Transaction.user_id == current_user.id,
            or_(
                func.lower(Transaction.category) == "other",
                Transaction.review_status == "needs_review"
            )
        )
    ).order_by(Transaction.created_at.desc())

    result = await db.execute(query)
    unreviewed = result.scalars().all()
    count = len(unreviewed)

    response.headers["X-Needs-Review-Count"] = str(count)

    return NeedsReviewResponse(
        count=count,
        transactions=unreviewed,
        message=f"Fix these {count} transactions to improve accuracy" if count > 0 else "All done for today! 🎉"
    )


@router.patch(
    "/{transaction_id}/categorize",
    summary="1-click categorize transaction & learn merchant mapping"
)
@router.patch(
    "/{transaction_id}/recategorize",
    summary="Re-categorize transaction alias endpoint"
)
async def categorize_transaction_item(
    transaction_id: int,
    payload: CategorizeRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    Updates transaction category, marks review_status as 'reviewed',
    and optionally saves merchant_alias mapping to merchant_mappings table for automatic learning.
    """
    res = await db.execute(select(Transaction).where(Transaction.id == transaction_id))
    transaction = res.scalars().first()

    if not transaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found"
        )

    if transaction.user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not authorized to edit this transaction"
        )

    req_category = normalize_category_name(payload.target_category)
    # Validate category against canonical list
    cat_match = next((c for c in CATEGORIES if c.lower() == req_category.lower()), None)
    if not cat_match:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid category '{req_category}'. Must be one of {CATEGORIES}"
        )

    transaction.category = cat_match
    transaction.review_status = "reviewed"

    learned = False
    target_alias = (payload.target_merchant or transaction.merchant or "").strip()
    if target_alias:
        # Same key + lookup as recategorize and ingest (merchant_key / find_user_correction)
        existing_map = await find_user_correction(db, current_user.id, target_alias)

        if existing_map:
            existing_map.category = cat_match
            existing_map.count += 1
            learned = True
        elif merchant_key(target_alias):
            new_map = MerchantMapping(
                user_id=current_user.id,
                merchant_key=merchant_key(target_alias),
                category=cat_match,
                display_name=target_alias.title()
            )
            db.add(new_map)
            learned = True

    await db.commit()
    await db.refresh(transaction)

    # Check budget alert threshold
    if transaction.type == "debit":
        await check_budget_and_alert(db, current_user.id, transaction.category, float(transaction.amount))

    return {"updated": True, "learned": learned, "category": cat_match}


