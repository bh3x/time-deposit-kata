# ADR-0004: Time deposit API design

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief requires exactly two operations: retrieve all time deposits (`id`, `planType`, `balance`, `days`,
`withdrawals`) and update the balances of all time deposits. Resources are designed first, independent of URIs,
following the conventions in ADR-0003.

## Options considered
**Identifiers:** expose the integer primary key · public UUID alongside the internal integer key · UUID primary key.
**Money:** binary floating point (`double`) · decimal number · decimal string · integer minor units · money object
with currency.
**Ordering:** unspecified · unspecified with deterministic implementation · documented default order.

## Decision
### Time Deposits (collection)
- Represented as a JSON array of Time Deposit; supported operation: **read the whole collection**.
- **Order is not guaranteed** by the contract. The implementation returns a deterministic order (internal key)
  so repeated calls are stable, without promising it. Sorting may be added later as an optional parameter.

### Time Deposit / Withdrawal
- Attributes exactly as the brief; withdrawals are embedded in their time deposit (dependent entity, no
  `timeDepositId` in the representation).
- **Identifiers:** a public **UUID** exposed as `id`; the internal integer primary key required by the brief and
  the legacy `TimeDeposit` class is never exposed (avoids leaking counts, enumeration and storage coupling).
- **Money:** decimal **string** with exactly two fraction digits (e.g. `"10000.00"`), as common in banking APIs;
  no binary floating point on the wire. **Single currency** assumed — the brief defines none.
- **planType:** string with `x-extensible-enum` (`basic`, `student`, `premium`); case-sensitive.
- `days` is a non-negative integer.

### Balance Update
_To be designed._

## Consequences
- The data model needs a unique public UUID per time deposit and withdrawal (data model ADR).
- The REST adapter converts the legacy `Double` balance to a 2-decimal string; the domain is unchanged.
- Adding a currency later is a deliberate, versioned change.
- Tests must not rely on collection order.
