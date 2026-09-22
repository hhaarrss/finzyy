"""
Pydantic schemas for User authentication and profiles.
"""

from datetime import date, datetime
from typing import Literal, Optional
from pydantic import BaseModel, EmailStr, ConfigDict, Field, computed_field


class UserBase(BaseModel):
    """Base schema containing common user fields."""

    email: EmailStr = Field(..., description="The unique email address of the user.")
    full_name: str = Field(..., max_length=100, description="The user's full name.")


class UserCreate(UserBase):
    """Schema for registering a new user."""

    password: str = Field(..., min_length=8, description="Strong user password (min 8 characters).")


class UserResponse(BaseModel):
    """Schema representing user profiles returned in API responses."""

    id: int
    email: Optional[str] = None
    phone_number: Optional[str] = None
    full_name: str = ""
    date_of_birth: Optional[date] = None
    gender: Optional[str] = None
    city: Optional[str] = None
    occupation: Optional[str] = None
    monthly_income: Optional[float] = None
    monthly_budget: Optional[float] = None
    family_id: Optional[int] = None
    profile_completed_at: Optional[datetime] = None
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def profile_complete(self) -> bool:
        return self.profile_completed_at is not None


class Token(BaseModel):
    """Schema for standard OAuth2 bearer tokens."""

    access_token: str
    token_type: str = "bearer"


class AuthResponse(Token):
    """
    Token plus what the app needs to decide where to go next:
    new or incomplete profiles go to profile setup, accounts without a
    phone number are asked to add one.
    """

    is_new_user: bool = False
    profile_complete: bool = False
    phone_number: Optional[str] = None
    email: Optional[str] = None
    full_name: str = ""


class TokenData(BaseModel):
    """Schema for data extracted from decoded JWT tokens."""

    email: Optional[str] = None
    user_id: Optional[int] = None


class FirebaseLoginRequest(BaseModel):
    """
    A Firebase ID token obtained on the device after Phone (OTP) or Google sign-in.
    The backend verifies it with the Firebase Admin SDK; it never sees the OTP itself.
    """

    id_token: str = Field(..., min_length=20, description="Firebase ID token.")


GenderOption = Literal["male", "female", "other", "prefer_not_to_say"]


class ProfileUpdate(BaseModel):
    """Profile details submitted from the profile setup / edit screen."""

    model_config = ConfigDict(extra="forbid")

    full_name: str = Field(..., min_length=2, max_length=100)
    email: Optional[EmailStr] = None
    date_of_birth: Optional[date] = None
    gender: Optional[GenderOption] = None
    city: Optional[str] = Field(None, max_length=100)
    occupation: Optional[str] = Field(None, max_length=100)
    monthly_income: Optional[float] = Field(None, ge=0, le=100_000_000)
    monthly_budget: Optional[float] = Field(None, gt=0, le=100_000_000)
