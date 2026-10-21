# ADR 0001: Keep separate commuter and driver tables for now

**Status:** accepted, revisit before the public launch.
**Date:** 2026-10-21

## Context

Commuters (`commuter_schema.users`), drivers (`driver_schema.drivers`) and
staff (`staff_users`) are separate tables, each with its own id sequence.
The code-health list included merging them into one users table with roles.

What the split looks like today:

- **Overlapping ids.** Commuter 42 and driver 42 are different people.
  Everywhere a person is referenced, the id is paired with a role: the JWT
  (`sub` + `role`), wallets, notifications, chat messages, push
  subscriptions, sessions, ratings. Elsewhere the column itself says which
  table it means (`bookings.commuter_id`, `driver_id`).
- **About 33 columns across the migrations** point at a person this way, and
  22 classes use `UserRepository`/`DriverRepository` (about 56 calls).
- **Two accounts per person.** Someone who drives and also rides needs two
  accounts, which can share an email address because each table checks
  uniqueness separately. Sign-in asks which kind of account.
- **Security code duplicated per table:** email/phone verification, password
  changes, deletion and `credentialsChangedAt` are each written once per
  table, behind the `AccountState` projection.

## Decision

Don't merge now. Keep the tables, and keep treating (role, id) as the
identity of a person.

A merge would mean:

- renumbering one side's ids and rewriting every reference in one
  migration, on live data that includes the money ledger;
- invalidating every session and push subscription;
- re-testing every ownership check.

That is a lot of risk for no change a pilot user would see. The current
design is consistent: the `(role, id)` pair is checked everywhere through
`CurrentUser`, and the tests cover ownership per role.

## When to revisit, and how

Revisit if one of these becomes true:

- people often hold both kinds of account (support asks to "link my accounts");
- account-security work keeps having to be written twice;
- a third self-service role appears.

The recommended shape then is **one identity, separate roles**, not a single
users table:

1. An `accounts` table holds what identifies a person and how they sign in:
   - email and phone;
   - password hash and verification state;
   - accepted terms;
   - `credentials_changed_at`, `deleted_at` and MFA.
2. `users` and `drivers` become profiles with an `account_id`. Their own ids
   stay as they are, so bookings, wallets and the ledger are not renumbered.
3. The JWT carries the account id plus the active role (and that role's
   profile id). Switching role becomes a token exchange, not a second
   sign-in.
4. Migrate in steps:
   1. Create `accounts` from the existing rows. Duplicate emails across
      tables become one account with two profiles, but only after the
      person proves they own both.
   2. Move the security code over to `accounts`.
   3. Drop the duplicated columns.

   Each step ships and runs on its own.

The plan above is about 2–3 weeks of work including migration testing on a
copy of production data. It does not touch the money tables.
