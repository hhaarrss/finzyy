"""
Router for Authentication and Profile Management.

Primary sign-in is phone number + OTP through Firebase Phone Auth; Google sign-in is the
secondary option. Both hand this backend a Firebase ID token, which is verified server-side
before this backend issues its own JWT. Email + password is kept for accounts created before
phone login, so those users can still sign in and then link a phone number.
"""

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy import func
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from database import get_db
from models.user import User
from schemas.user import AuthResponse, FirebaseLoginRequest, UserCreate, UserResponse
from services.accounts import build_user_response, issue_auth_response
from utils.auth import hash_password, verify_password
from utils.dependencies import get_current_user
from utils.firebase_admin_client import verify_firebase_id_token

router = APIRouter(prefix="/auth", tags=["Authentication"])


def _sign_in_provider(claims: dict) -> str:
    return (claims.get("firebase") or {}).get("sign_in_provider", "")


@router.post(
    "/firebase",
    response_model=AuthResponse,
    summary="Sign in (or sign up) with a Firebase ID token from Phone OTP or Google",
)
async def firebase_login(
    payload: FirebaseLoginRequest,
    db: AsyncSession = Depends(get_db),
) -> AuthResponse:
    """
    Exchanges a verified Firebase ID token for this app's own JWT.

    - Phone sign-in: the account is identified by the verified phone number.
    - Google sign-in: the account is identified by the Google-verified email, so an
      existing email/password account with the same address is signed into, not duplicated.

    A new account is created on first sign-in; `profile_complete` tells the app whether to
    show the profile setup screen next.
    """
    claims = verify_firebase_id_token(payload.id_token)
    provider = _sign_in_provider(claims)
    phone_number = claims.get("phone_number")
    email = (claims.get("email") or "").strip().lower() or None

    user: User | None = None
    is_new_user = False

    if phone_number:
        result = await db.execute(select(User).where(User.phone_number == phone_number))
        user = result.scalars().first()
        if user is None:
            user = User(phone_number=phone_number, full_name="")
            db.add(user)
            is_new_user = True
    elif email and claims.get("email_verified") and provider == "google.com":
        result = await db.execute(select(User).where(func.lower(User.email) == email))
        user = result.scalars().first()
        if user is None:
            user = User(email=email, full_name=(claims.get("name") or "")[:100])
            db.add(user)
            is_new_user = True
    else:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This sign-in has no verified phone number or Google email.",
        )

    await db.commit()
    await db.refresh(user)
    return issue_auth_response(user, is_new_user=is_new_user)


@router.post(
    "/link-phone",
    response_model=UserResponse,
    summary="Attach a verified phone number to the signed-in account",
)
async def link_phone(
    payload: FirebaseLoginRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> UserResponse:
    """
    Moves an email/password or Google account over to phone login: after the user verifies
    their number with OTP, the phone is saved on their existing account so future phone
    sign-ins open the same data.
    """
    claims = verify_firebase_id_token(payload.id_token)
    phone_number = claims.get("phone_number")
    if not phone_number:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Verify a phone number with OTP first.",
        )

    result = await db.execute(select(User).where(User.phone_number == phone_number))
    owner = result.scalars().first()
    if owner is not None and owner.id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This number is already linked to another Finzyy account.",
        )

    current_user.phone_number = phone_number
    await db.commit()
    await db.refresh(current_user)
    return await build_user_response(db, current_user)


@router.post(
    "/register",
    response_model=AuthResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Register with email + password (legacy; the app uses phone sign-in)",
)
async def register(
    user_in: UserCreate,
    db: AsyncSession = Depends(get_db)
) -> AuthResponse:
    """
    Registers a new user by checking for duplicates, hashing the password, and committing.
    Returns a JWT token so the client can immediately start using the app.
    """
    email = user_in.email.lower()
    result = await db.execute(select(User).where(func.lower(User.email) == email))
    if result.scalars().first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered",
        )

    new_user = User(
        email=email,
        hashed_password=hash_password(user_in.password),
        full_name=user_in.full_name,
    )
    db.add(new_user)
    await db.commit()
    await db.refresh(new_user)
    return issue_auth_response(new_user, is_new_user=True)


@router.post(
    "/login",
    response_model=AuthResponse,
    summary="Sign in with email + password (accounts created before phone login)",
)
async def login(
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: AsyncSession = Depends(get_db)
) -> AuthResponse:
    """
    Authenticates a user using standard OAuth2 Password Request Form (username=email, password).
    """
    result = await db.execute(select(User).where(func.lower(User.email) == form_data.username.strip().lower()))
    user = result.scalars().first()

    if not user or not user.hashed_password or not verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return issue_auth_response(user)


@router.get(
    "/me",
    response_model=UserResponse,
    summary="Fetch the current authenticated user profile",
)
async def get_me(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> UserResponse:
    """Returns the profile of the current authenticated user."""
    return await build_user_response(db, current_user)
