"""Administration commands; passwords are never accepted on the command line."""

import argparse
import getpass
import json
import os
import sys

from .store import Store


def main() -> None:
    parser = argparse.ArgumentParser(description="Manage Trinity gateway users and Android device enrollment")
    parser.add_argument("--database", default=os.environ.get("TRINITY_DATABASE", "/data/trinity.sqlite3"))
    commands = parser.add_subparsers(dest="command", required=True)
    create_user = commands.add_parser("create-user", help="Create a login account; password from prompt or TRINITY_USER_PASSWORD")
    create_user.add_argument("username")
    create_device = commands.add_parser("create-device", help="Print a new device credential once")
    create_device.add_argument("username")
    create_device.add_argument("device_id")
    revoke_device = commands.add_parser("revoke-device", help="Revoke credentials for one user's device")
    revoke_device.add_argument("username")
    revoke_device.add_argument("device_id")
    args = parser.parse_args()
    store = Store(args.database)
    try:
        if args.command == "create-user":
            password = os.environ.get("TRINITY_USER_PASSWORD") or getpass.getpass("Password: ")
            subject = store.create_user(args.username, password)
            print(json.dumps({"username": args.username, "subject": subject}))
            return
        user = store.get_user(args.username)
        if user is None:
            raise ValueError("User does not exist")
        if args.command == "create-device":
            credential = store.create_device_token(user["subject"], args.device_id)
            # This command explicitly returns a credential for the operator to enroll the app.
            print(json.dumps({"device_id": args.device_id, "principal": user["subject"], "device_token": credential}))
        else:
            store.revoke_device(user["subject"], args.device_id)
            print(json.dumps({"device_id": args.device_id, "revoked": True}))
    except (ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
