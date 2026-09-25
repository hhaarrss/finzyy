"""
Pydantic schemas for Transaction verification and response serialization.
"""

from datetime import datetime
from typing import Optional, Dict, List
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class TransactionBase(BaseModel):
    """Base schema for Transaction validation."""

    amount: float = Field(..., gt=0, description="Amount must be positive.")
    type: str = Field(..., description="Transaction type: 'debit' or 'credit'.")
    category: str = Field(..., max_length=100, description="Spending or income category.")
    merchant: Optional[str] = Field(None, max_length=255, description="Name of the merchant.")
    bank: Optional[str] = Field(None, max_length=50, description="Name of the banking institution.")
    account_last4: Optional[str] = Field(
        None, max_length=50, description="Last digits/identifier of the account number."
    )
    date: datetime = Field(..., description="The transaction occurrence date and time.")
    source: str = Field(
        "manual", description="Source of transaction registration."
    )
    is_transfer: bool = Field(False, description="True if transaction is a P2P transfer.")
    transfer_to: Optional[str] = Field(None, description="Name/handle of transfer recipient.")

    @field_validator("type")
    @classmethod
    def validate_type(cls, v: str) -> str:
        """Validates that transaction type is either credit or debit."""
        val = v.lower()
        if val not in ("credit", "debit"):
            raise ValueError("Type must be either 'credit' or 'debit'")
        return val

    @field_validator("source")
    @classmethod
    def validate_source(cls, v: str) -> str:
        """Validates that source is one of the allowed platforms."""
        val = v.lower()
        allowed_sources = {"sms", "aa", "manual", "merchant_db", "user_correction", "mcc_codes", "fallback", "keyword_rules"}
        if val not in allowed_sources:
            raise ValueError(f"Source must be one of {allowed_sources}")
        return val


class TransactionCreate(TransactionBase):
    """Schema for adding new transactions."""

    notes: Optional[str] = None


class TransactionUpdate(BaseModel):
    """Schema for editing an existing transaction."""

    category: Optional[str] = Field(None, max_length=100, description="Updated category name.")
    merchant: Optional[str] = Field(None, max_length=255, description="Updated merchant name.")
    amount: Optional[float] = Field(None, gt=0, description="Updated amount (must be positive).")
    date: Optional[datetime] = Field(None, description="Updated transaction date/time.")
    notes: Optional[str] = Field(None, description="Optional user notes.")
    is_transfer: Optional[bool] = Field(None, description="Updated transfer flag.")


class TransactionResponse(TransactionBase):
    """Schema representing transactions returned in API responses."""

    id: int
    user_id: int
    hash_fingerprint: Optional[str] = None
    subcategory: Optional[str] = None
    upi_ref: Optional[str] = None
    confidence: Optional[str] = None
    review_status: Optional[str] = "reviewed"
    notes: Optional[str] = None
    created_at: datetime
    merchant_display: Optional[str] = None
    merchant_raw: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

    @model_validator(mode="after")
    def show_brand_name(self):
        """
        `merchant` in a response is what a screen should show: the recognised brand name when
        there is one, otherwise exactly what the bank sent. The stored raw text is always in
        `merchant_raw` (it is the key for user corrections). Only this response object is
        changed - the database row is untouched, so older app builds keep working.
        """
        if self.merchant_raw is None:
            self.merchant_raw = self.merchant
        if self.merchant_display:
            self.merchant = self.merchant_display
        return self


class TransactionSummaryResponse(BaseModel):
    """Schema for transaction aggregate totals (daily, monthly, yearly)."""

    daily: Dict[str, float] = Field(default_factory=dict, description="Daily spending/income aggregates.")
    monthly: Dict[str, float] = Field(default_factory=dict, description="Monthly spending/income aggregates.")
    yearly: Dict[str, float] = Field(default_factory=dict, description="Yearly spending/income aggregates.")


class CorrectionRequest(BaseModel):
    """Schema for transaction re-categorization correction requests."""
    transaction_id: Optional[int] = Field(None, description="Target transaction ID.")
    merchant_raw: Optional[str] = Field(None, description="Raw merchant name to correct future categorizations.")
    new_category: Optional[str] = Field(None, description="Corrected category name.")
    subcategory: Optional[str] = Field(None, description="Optional corrected subcategory.")
    display_name: Optional[str] = Field(None, description="Optional clean merchant name.")


class PaginatedTransactionResponse(BaseModel):
    """Schema for paginated transaction list responses."""

    transactions: List[TransactionResponse]
    total_count: int = Field(..., description="Total number of matching transactions.")
    page: int = Field(..., description="Current page number.")
    limit: int = Field(..., description="Number of items per page.")
    has_more: bool = Field(..., description="True if more pages exist.")
    total_pages: int = Field(..., description="Total number of available pages.")


class CategorySummaryItem(BaseModel):
    """Schema for a single category total item in monthly summary."""

    category: str = Field(..., description="Category name.")
    total: float = Field(..., description="Total amount spent in this category.")
    percentage: float = Field(..., description="Percentage of total monthly spend.")
    transaction_count: int = Field(..., description="Number of transactions in this category.")
    top_merchant: str = Field(..., description="Merchant with highest spend in this category.")
    budget_limit: float = Field(..., description="Configured monthly budget limit for this category.")
    budget_used_percent: float = Field(..., description="Percentage of budget limit utilized.")


class MonthlyCategorySummaryResponse(BaseModel):
    """Schema for monthly category spending summary response."""

    month: int = Field(..., ge=1, le=12, description="Target month (1-12).")
    year: int = Field(..., ge=2020, le=2030, description="Target year (2020-2030).")
    total_spent: float = Field(..., description="Total amount spent across all categories in the month.")
    merchant_spent: float = Field(0.0, description="Amount spent exclusively on merchants.")
    transfer_sent: float = Field(0.0, description="Amount transferred to people (debit transfers).")
    transfer_received: float = Field(0.0, description="Amount received from people (credit transfers).")
    categories: List[CategorySummaryItem] = Field(..., description="List of category summary items sorted by total DESC.")
    previous_month_total: float = Field(..., description="Total amount spent in the previous month.")
    month_over_month_change: float = Field(..., description="Percentage change compared to previous month.")


class NeedsReviewResponse(BaseModel):
    """Schema for needs-review transactions count and list."""

    count: int = Field(..., description="Total unreviewed transactions count.")
    transactions: List[TransactionResponse] = Field(..., description="Unreviewed transaction items.")
    message: str = Field("Fix these transactions to improve accuracy", description="User facing header message.")


class CategorizeRequest(BaseModel):
    """Schema for 1-click categorization request."""

    category: Optional[str] = Field(None, max_length=100, description="Target canonical category.")
    new_category: Optional[str] = Field(None, max_length=100, description="Alternative field for target category.")
    merchant_alias: Optional[str] = Field(None, description="Optional merchant/VPA name to map to this category for future transactions.")
    merchant_raw: Optional[str] = Field(None, description="Alternative field for merchant name.")

    @property
    def target_category(self) -> str:
        return self.category or self.new_category or "Other"

    @property
    def target_merchant(self) -> Optional[str]:
        return self.merchant_alias or self.merchant_raw

