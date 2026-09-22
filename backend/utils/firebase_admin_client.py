"""
Firebase Admin SDK integration for verifying client-side Phone (OTP) and Google sign-ins.

The mobile app completes OTP verification (or Google sign-in) against Firebase directly —
Firebase owns SMS delivery and OTP validation — and hands this backend a Firebase ID token as proof.
This module verifies that token's signature and claims server-side — the backend
never sees or handles the OTP itself.

REQUIRED SETUP (not done by this code, must be done once per environment):
1. In the Firebase Console for this project, enable the "Phone" and "Google" sign-in
   providers under Authentication > Sign-in method.
2. Generate a service account key: Project Settings > Service Accounts >
   Generate new private key. This downloads a JSON file — treat it as a secret,
   never commit it.
3. Set ONE of these environment variables before starting the backend:
   - FIREBASE_SERVICE_ACCOUNT_JSON: the full JSON file contents, as a string
     (convenient for platforms like Render where you paste an env var value).
   - GOOGLE_APPLICATION_CREDENTIALS: an absolute path to the JSON file on disk.
Until one of these is set, verify_firebase_id_token() raises a 503 rather than
crashing the app at import time — the rest of the API stays usable.
"""

import json
import os
from typing import Any, Dict, Optional

from fastapi import HTTPException, status

_firebase_app: Optional[Any] = None
_init_attempted = False


def _initialize() -> Optional[Any]:
    """Lazily initializes the Firebase Admin app on first use. Idempotent."""
    global _firebase_app, _init_attempted
    if _firebase_app is not None or _init_attempted:
        return _firebase_app
    _init_attempted = True

    try:
        import firebase_admin
        from firebase_admin import credentials
    except ImportError:
        return None

    service_account_json = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")
    credentials_path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")

    try:
        if service_account_json:
            cred = credentials.Certificate(json.loads(service_account_json))
        elif credentials_path:
            cred = credentials.Certificate(credentials_path)
        else:
            return None
        _firebase_app = firebase_admin.initialize_app(cred)
    except Exception:
        _firebase_app = None

    return _firebase_app


def verify_firebase_id_token(id_token: str) -> Dict[str, Any]:
    """
    Verifies a Firebase ID token and returns its decoded claims.

    Args:
        id_token: The Firebase ID token from the client's phone or Google sign-in.

    Raises:
        HTTPException: 503 if Firebase Admin isn't configured on this deployment,
            401 if the token is missing, expired, or invalid.

    Returns:
        The decoded token claims (includes "phone_number" for phone-auth sign-ins).
    """
    app = _initialize()
    if app is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "Phone sign-in is not configured on this server. Set "
                "FIREBASE_SERVICE_ACCOUNT_JSON or GOOGLE_APPLICATION_CREDENTIALS."
            ),
        )

    from firebase_admin import auth as firebase_auth

    try:
        return firebase_auth.verify_id_token(id_token, app=app)
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired sign-in token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
