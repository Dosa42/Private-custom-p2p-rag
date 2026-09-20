"""Persistent identities and single-use OAuth grants for the Trinity gateway.

Only SHA-256 digests of cryptographically random bearer credentials are stored.
Passwords use salted scrypt. All grant consumption/rotation is atomic in SQLite.
"""

from __future__ import annotations

from contextlib import contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time
from typing import Iterator
import uuid


ACCESS_TOKEN_SECONDS = 900
REFRESH_TOKEN_SECONDS = 30 * 24 * 60 * 60
TRANSACTION_SECONDS = 600
CODE_SECONDS = 60


class GrantError(ValueError):
    def __init__(self, error: str, description: str) -> None:
        super().__init__(description)
        self.error = error
        self.description = description


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _secret() -> str:
    return secrets.token_urlsafe(32)


def _password_hash(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    derived = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=16384, r=8, p=1,
                             dklen=32, maxmem=64 * 1024 * 1024)
    return f"scrypt$16384$8$1${salt.hex()}${derived.hex()}"


_DUMMY_PASSWORD = _password_hash("unusable-dummy-password", bytes(16))


def _password_matches(password: str, encoded: str | None) -> bool:
    encoded = encoded or _DUMMY_PASSWORD
    try:
        _, n, r, p, salt, expected = encoded.split("$")
        actual = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt),
                                 n=int(n), r=int(r), p=int(p), dklen=32,
                                 maxmem=64 * 1024 * 1024)
        return hmac.compare_digest(actual, bytes.fromhex(expected))
    except (ValueError, TypeError):
        return False


class Store:
    def __init__(self, db_path: str | Path) -> None:
        self.db_path = str(Path(db_path).expanduser().resolve())
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        # Create privately before SQLite creates its journaling files.
        descriptor = os.open(self.db_path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        os.chmod(self.db_path, 0o600)
        with self._connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    subject TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS clients (
                    client_id TEXT PRIMARY KEY, client_name TEXT NOT NULL,
                    redirect_uris TEXT NOT NULL, scope TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS authorization_transactions (
                    transaction_hash TEXT PRIMARY KEY, csrf_hash TEXT NOT NULL,
                    browser_hash TEXT NOT NULL, client_id TEXT NOT NULL,
                    redirect_uri TEXT NOT NULL, state TEXT NOT NULL, scope TEXT NOT NULL,
                    resource TEXT NOT NULL, challenge TEXT NOT NULL,
                    expires_at INTEGER NOT NULL, consumed INTEGER NOT NULL DEFAULT 0,
                    failed_attempts INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(client_id) REFERENCES clients(client_id)
                );
                CREATE TABLE IF NOT EXISTS authorization_codes (
                    code_hash TEXT PRIMARY KEY, subject TEXT NOT NULL, client_id TEXT NOT NULL,
                    redirect_uri TEXT NOT NULL, scope TEXT NOT NULL, resource TEXT NOT NULL,
                    challenge TEXT NOT NULL, expires_at INTEGER NOT NULL,
                    consumed INTEGER NOT NULL DEFAULT 0, family_id TEXT,
                    FOREIGN KEY(subject) REFERENCES users(subject)
                );
                CREATE TABLE IF NOT EXISTS access_tokens (
                    token_hash TEXT PRIMARY KEY, subject TEXT NOT NULL, client_id TEXT NOT NULL,
                    scope TEXT NOT NULL, resource TEXT NOT NULL, expires_at INTEGER NOT NULL,
                    family_id TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(subject) REFERENCES users(subject)
                );
                CREATE INDEX IF NOT EXISTS access_token_family ON access_tokens(family_id);
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    token_hash TEXT PRIMARY KEY, subject TEXT NOT NULL, client_id TEXT NOT NULL,
                    scope TEXT NOT NULL, resource TEXT NOT NULL, expires_at INTEGER NOT NULL,
                    family_id TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0,
                    revoked INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(subject) REFERENCES users(subject)
                );
                CREATE INDEX IF NOT EXISTS refresh_token_family ON refresh_tokens(family_id);
                CREATE TABLE IF NOT EXISTS device_tokens (
                    token_hash TEXT PRIMARY KEY, subject TEXT NOT NULL, device_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(subject) REFERENCES users(subject)
                );
            """)

    @contextmanager
    def _connection(self, *, write: bool = False) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.db_path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            if write:
                db.execute("BEGIN IMMEDIATE")
            yield db
            if write:
                db.commit()
        except BaseException:
            if write:
                db.rollback()
            raise
        finally:
            db.close()

    def create_user(self, username: str, password: str) -> str:
        if not username or username != username.strip() or len(username) > 128:
            raise ValueError("Username must contain 1–128 characters without surrounding whitespace")
        if any(ord(c) < 32 for c in username):
            raise ValueError("Username cannot contain control characters")
        if not password or len(password.encode("utf-8")) > 1024:
            raise ValueError("Password must contain 1–1024 UTF-8 bytes")
        subject = str(uuid.uuid4())
        try:
            with self._connection(write=True) as db:
                db.execute("INSERT INTO users VALUES (?, ?, ?, ?)",
                           (subject, username, _password_hash(password), int(time.time())))
        except sqlite3.IntegrityError as error:
            raise ValueError("Username already exists") from error
        return subject

    def create_device_token(self, subject: str, device_id: str) -> str:
        if not device_id or len(device_id) > 128 or any(ord(c) < 32 for c in device_id):
            raise ValueError("Device ID must contain 1–128 non-control characters")
        token = _secret()
        with self._connection(write=True) as db:
            # Provisioning a replacement for a device invalidates its old credential.
            db.execute("UPDATE device_tokens SET revoked=1 WHERE subject=? AND device_id=?",
                       (subject, device_id))
            db.execute("INSERT INTO device_tokens VALUES (?, ?, ?, ?, 0)",
                       (_digest(token), subject, device_id, int(time.time())))
        return token

    def get_user(self, username: str) -> dict | None:
        with self._connection() as db:
            row = db.execute("SELECT subject, username, created_at FROM users WHERE username=?",
                             (username,)).fetchone()
        return dict(row) if row else None

    def revoke_device(self, subject: str, device_id: str) -> None:
        with self._connection(write=True) as db:
            db.execute("UPDATE device_tokens SET revoked=1 WHERE subject=? AND device_id=?",
                       (subject, device_id))

    def verify_device_token(self, raw: str) -> dict | None:
        if not raw or len(raw) > 1024:
            return None
        with self._connection() as db:
            row = db.execute("SELECT subject, device_id FROM device_tokens WHERE token_hash=? AND revoked=0",
                             (_digest(raw),)).fetchone()
        return dict(row) if row else None

    def verify_access_token(self, raw: str) -> dict | None:
        if not raw or len(raw) > 1024:
            return None
        with self._connection() as db:
            row = db.execute("""SELECT subject, client_id, scope, expires_at, resource FROM access_tokens
                              WHERE token_hash=? AND revoked=0 AND expires_at>?""",
                             (_digest(raw), int(time.time()))).fetchone()
        return dict(row) if row else None

    def register_client(self, name: str, redirect_uris: list[str], scope: str) -> dict:
        client_id = _secret()
        created_at = int(time.time())
        with self._connection(write=True) as db:
            db.execute("INSERT INTO clients VALUES (?, ?, ?, ?, ?)",
                       (client_id, name, json.dumps(redirect_uris), scope, created_at))
        return {"client_id": client_id, "client_name": name, "redirect_uris": redirect_uris,
                "scope": scope, "client_id_issued_at": created_at,
                "token_endpoint_auth_method": "none", "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"]}

    def get_client(self, client_id: str) -> dict | None:
        with self._connection() as db:
            row = db.execute("SELECT * FROM clients WHERE client_id=?", (client_id,)).fetchone()
        if not row:
            return None
        result = dict(row)
        result["redirect_uris"] = json.loads(result["redirect_uris"])
        return result

    def create_authorization_transaction(self, *, client_id: str, redirect_uri: str,
                                         state: str, scope: str, resource: str,
                                         challenge: str) -> dict:
        transaction, csrf, browser = _secret(), _secret(), _secret()
        with self._connection(write=True) as db:
            db.execute("""INSERT INTO authorization_transactions
                       (transaction_hash, csrf_hash, browser_hash, client_id, redirect_uri, state,
                        scope, resource, challenge, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                       (_digest(transaction), _digest(csrf), _digest(browser), client_id, redirect_uri,
                        state, scope, resource, challenge, int(time.time()) + TRANSACTION_SECONDS))
        return {"transaction": transaction, "csrf": csrf, "browser": browser}

    def complete_authorization(self, *, transaction: str, csrf: str, browser: str,
                               username: str, password: str, approve: bool) -> dict:
        result = None
        failed_login = False
        with self._connection(write=True) as db:
            row = db.execute("SELECT * FROM authorization_transactions WHERE transaction_hash=?",
                             (_digest(transaction),)).fetchone()
            if (not row or row["consumed"] or row["expires_at"] <= int(time.time())
                    or not hmac.compare_digest(row["csrf_hash"], _digest(csrf))
                    or not hmac.compare_digest(row["browser_hash"], _digest(browser))):
                raise GrantError("invalid_request", "Invalid or expired authorization transaction")
            result = {"redirect_uri": row["redirect_uri"], "state": row["state"]}
            if not approve:
                result["error"] = "access_denied"
            else:
                user = db.execute("SELECT subject, password_hash FROM users WHERE username=?",
                                  (username,)).fetchone()
                password_ok = _password_matches(password, user["password_hash"] if user else None)
                if not password_ok or not user:
                    db.execute("""UPDATE authorization_transactions SET failed_attempts=failed_attempts+1,
                                  consumed=CASE WHEN failed_attempts>=9 THEN 1 ELSE 0 END
                                  WHERE transaction_hash=?""", (_digest(transaction),))
                    failed_login = True
                else:
                    code = _secret()
                    db.execute("""INSERT INTO authorization_codes
                               (code_hash, subject, client_id, redirect_uri, scope, resource, challenge, expires_at)
                               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                               (_digest(code), user["subject"], row["client_id"], row["redirect_uri"],
                                row["scope"], row["resource"], row["challenge"], int(time.time()) + CODE_SECONDS))
                    result["code"] = code
            if not failed_login:
                db.execute("UPDATE authorization_transactions SET consumed=1 WHERE transaction_hash=?",
                           (_digest(transaction),))
        if failed_login:
            raise GrantError("access_denied", "Invalid username or password; restart authorization to retry")
        assert result is not None
        return result

    @staticmethod
    def _revoke_family(db: sqlite3.Connection, family_id: str) -> None:
        db.execute("UPDATE access_tokens SET revoked=1 WHERE family_id=?", (family_id,))
        db.execute("UPDATE refresh_tokens SET revoked=1 WHERE family_id=?", (family_id,))

    @staticmethod
    def _issue_tokens(db: sqlite3.Connection, *, subject: str, client_id: str, scope: str,
                      resource: str, family_id: str, refresh_expires_at: int) -> dict:
        access, refresh = _secret(), _secret()
        now = int(time.time())
        db.execute("INSERT INTO access_tokens VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                   (_digest(access), subject, client_id, scope, resource,
                    now + ACCESS_TOKEN_SECONDS, family_id))
        db.execute("INSERT INTO refresh_tokens VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
                   (_digest(refresh), subject, client_id, scope, resource,
                    refresh_expires_at, family_id))
        return {"access_token": access, "token_type": "Bearer", "expires_in": ACCESS_TOKEN_SECONDS,
                "refresh_token": refresh, "scope": scope}

    def exchange_code(self, *, code: str, client_id: str, redirect_uri: str,
                      challenge: str, resource: str) -> dict:
        result = None
        replayed = False
        with self._connection(write=True) as db:
            row = db.execute("SELECT * FROM authorization_codes WHERE code_hash=?", (_digest(code),)).fetchone()
            if (not row or row["client_id"] != client_id or row["redirect_uri"] != redirect_uri
                    or row["resource"] != resource or not hmac.compare_digest(row["challenge"], challenge)):
                raise GrantError("invalid_grant", "Invalid authorization code or grant binding")
            if row["consumed"]:
                if row["family_id"]:
                    self._revoke_family(db, row["family_id"])
                replayed = True
            elif row["expires_at"] <= int(time.time()):
                raise GrantError("invalid_grant", "Authorization code has expired")
            else:
                family_id = str(uuid.uuid4())
                db.execute("UPDATE authorization_codes SET consumed=1, family_id=? WHERE code_hash=?",
                           (family_id, _digest(code)))
                result = self._issue_tokens(db, subject=row["subject"], client_id=client_id, scope=row["scope"],
                                            resource=resource, family_id=family_id,
                                            refresh_expires_at=int(time.time()) + REFRESH_TOKEN_SECONDS)
        if replayed:
            raise GrantError("invalid_grant", "Authorization code has already been consumed")
        assert result is not None
        return result

    def refresh(self, *, token: str, client_id: str, resource: str, scope: str | None) -> dict:
        result = None
        replayed = False
        with self._connection(write=True) as db:
            row = db.execute("SELECT * FROM refresh_tokens WHERE token_hash=?", (_digest(token),)).fetchone()
            if (not row or row["client_id"] != client_id or row["resource"] != resource):
                raise GrantError("invalid_grant", "Invalid refresh token or grant binding")
            if row["used"]:
                self._revoke_family(db, row["family_id"])
                replayed = True
            elif row["revoked"] or row["expires_at"] <= int(time.time()):
                raise GrantError("invalid_grant", "Refresh token is expired or revoked")
            else:
                granted_scope = row["scope"] if scope is None else scope
                if not set(granted_scope.split()).issubset(row["scope"].split()):
                    raise GrantError("invalid_scope", "Refresh cannot expand the original grant")
                db.execute("UPDATE refresh_tokens SET used=1 WHERE token_hash=?", (_digest(token),))
                result = self._issue_tokens(db, subject=row["subject"], client_id=client_id, scope=granted_scope,
                                            resource=resource, family_id=row["family_id"],
                                            refresh_expires_at=row["expires_at"])
        if replayed:
            raise GrantError("invalid_grant", "Refresh token reuse detected; the token family was revoked")
        assert result is not None
        return result

    def revoke(self, token: str, client_id: str) -> None:
        with self._connection(write=True) as db:
            row = db.execute("SELECT family_id FROM refresh_tokens WHERE token_hash=? AND client_id=?",
                             (_digest(token), client_id)).fetchone()
            if row:
                self._revoke_family(db, row["family_id"])
            else:
                db.execute("UPDATE access_tokens SET revoked=1 WHERE token_hash=? AND client_id=?",
                           (_digest(token), client_id))
