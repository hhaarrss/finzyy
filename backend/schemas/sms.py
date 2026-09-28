"""Pydantic schemas for on-device parsed transaction ingestion."""

from datetime import datetime
from typing import Literal, Optional
from pydantic import BaseModel, ConfigDict, Field
from schemas.transaction import TransactionResponse


class SMSIngestionRequest(BaseModel):
    """Structured transaction data parsed on-device; no SMS body is accepted."""

    model_config = ConfigDict(extra="forbid")

    amount: float = Field(..., gt=0, description="Transaction amount.")
    transaction_type: Literal["debit", "credit"] = Field(..., description="Debit or credit.")
    merchant_raw: Optional[str] = Field(None, max_length=255, description="Parsed merchant or payee name.")
    bank_sender_id: Optional[str] = Field(None, max_length=50, description="Bank SMS sender ID.")
    account_last4: Optional[str] = Field(None, max_length=4, description="Masked account suffix.")
    date: datetime = Field(..., description="Transaction timestamp parsed on-device.")
    upi_ref: Optional[str] = Field(None, max_length=100, description="UPI reference number.")


class SMSIngestionResponse(BaseModel):
    """
    Schema representing the ingestion result response.
    """

    success: bool = Field(..., description="Indicates if the SMS was successfully parsed and saved.")
    transaction: Optional[TransactionResponse] = Field(None, description="The created transaction details if successful.")
    message: str = Field(..., description="Feedback message regarding the ingestion status.")
    # For the app's "new transaction" notification, so it needs no extra request. Optional:
    # absent on failures/duplicates, and older app builds simply ignore them.
    merchant_visit_count: Optional[int] = Field(
        None, description="How many debits this user now has at this merchant, including this one."
    )
    month_spent: Optional[float] = Field(
        None, description="This month's total spend, the same figure the Home screen shows."
    )
