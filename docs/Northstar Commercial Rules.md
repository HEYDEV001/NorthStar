# Northstar Commercial Rules

Oct 22, 2026 · @Abhishek

## 1. Principles

Northstar records, calculates and documents money for each business; it never holds funds, and every tax rule here must be reviewed by a chartered accountant before launch.

1. **Every accepted price is tied to one quote version.** Prices on a sent quote never change.
2. **Every rupee is a ledger entry.** Amount due is calculated, never typed.
3. **Paid means Razorpay says paid.** No screenshot, message, AI output or manual tick marks a payment paid (a staff-recorded cash payment is a separate, audited entry type, if enabled).
4. **Financial documents are never deleted.** Corrections happen through new versions, cancellations and credit notes.
5. **Money is integers.** All amounts in paise; tax computed per line and rounded once, by a fixed rule.
6. **Commitments need permission.** Discounts above limit, refunds and cancellations after payment always need a human.

## 2. Catalogue, prices and tax settings

Catalogue prices are stored exclusive of GST; tax is added per line at quote time from the item's rate.

**Business tax profile (set in onboarding)**

| Field | Rule |
| --- | --- |
| GSTIN | 15 characters, format-validated; first two digits must match the state code |
| Legal name, address, state code | Printed on every document |
| Registration type | Regular / composition / unregistered; composition and unregistered businesses get a "bill of supply" or plain receipt instead of a tax invoice (CA to confirm wording) |
| Invoice prefix | Up to 6 characters, e.g. `RLR` |
| Financial year start | 1 April (Indian FY) |

**Catalogue item**

| Field | Rule |
| --- | --- |
| Name, description | Shown on quote and invoice |
| Unit price | Paise, exclusive of GST |
| HSN/SAC | Required for registered businesses |
| GST rate | One of the configured rates; since 22 September 2025 the main slabs are 0, 5, 18 and 40% (\[Tally\](https://tallysolutions.com/gst/new-gst-rate-structure/)). Default 18%, as most services fall there; the business confirms per item |
| Active | Inactive items can't be added to new quotes; existing quotes unaffected |

The GST rate list itself is configuration, not code, because rates and slabs can change.

## 3. Quotes

A quote is a series of immutable versions; the client can accept only the latest sent version, and that acceptance fixes the price for everything that follows.

**Version rules**

- Lines are copied from the catalogue at creation (name, price, HSN/SAC, rate); later catalogue edits don't touch the quote.
- Any change (line, quantity, price, discount, validity) creates version n+1; the previous sent version becomes Superseded.
- Statuses: Draft → Needs Review (approval mode) → Sent → Accepted | Changes Requested | Superseded | Expired.
- Validity: default 7 days from sending (configurable); after it, the version expires and the client is offered a refreshed quote.

**Discounts**

| Rule | Default |
| --- | --- |
| Applied as | Percentage or fixed amount, on the quote total before tax (pro-rated across lines for tax) |
| Staff limit | 10% without admin approval (configurable) |
| Above limit | Admin approval required in both modes |
| AI | May never offer or agree to a discount |

**Acceptance**

- Through the Accept button on WhatsApp (button ID carries the version ID) or by staff recording a verbal acceptance (logged with who and when).
- Acceptance on a superseded or expired version is refused and the latest version re-sent.
- Accepted version → payment plan created: advance and balance amounts fixed at this moment.

**Quote numbering:** `Q-{prefix}-{FY}-{sequence}-v{version}`, e.g. `Q-RLR-2026-27-0042-v2`. Quote numbers are not tax documents and may skip.

## 4. Payment ledger

Each payment request and each payment received is its own ledger row; the amount still due on a lead is always derived from the accepted quote and the paid rows.

```latex
\text{amount due} = \text{accepted quote total (incl. GST)} - \sum \text{paid} + \sum \text{refunded}
```

**Payment kinds**

| Kind | Amount | When created |
| --- | --- | --- |
| Full | Quote total | On acceptance, when advance is off |
| Advance | Quote total × advance %, rounded to the nearest rupee | On acceptance |
| Partial | Whatever the client paid below the requested amount | From `payment_link.partially_paid` |
| Balance | Amount due at the moment the work is marked done | On In progress → done |
| Fee / instalment | Per template (coaching) | Per schedule |

**Statuses:** Pending → Paid | Partially paid | Failed | Expired; after payment: Refund due | Partially refunded | Refunded.

**Rules**

- Advance % is fixed when the quote is accepted; changing the setting later does not affect accepted quotes.
- A client paying more than due → excess flagged Refund due for admin; never kept silently.
- Only one active (pending) payment link per lead at a time; creating a new one expires the old link at Razorpay first.
- Cash or bank transfer received outside Razorpay (if the business enables it): staff records it with a reference and attachment; admin-only; audited; appears as kind `manual`.

## 5. GST documents and tax calculation

The product flow says "invoice after payment", but under GST an advance for a service not yet delivered is usually documented with a receipt voucher, and the tax invoice follows the service; the CA must confirm which documents each business issues and when.

**Proposed document set (pending CA review)**

| Moment | Document | Notes |
| --- | --- | --- |
| Advance received, service not yet done | Receipt voucher | Shows advance amount and tax on it |
| Service delivered / job done | Tax invoice for the full value | Adjusts advance already taxed; balance shown due |
| Full payment received after delivery | Tax invoice (if not already issued) |  |
| Cancellation / refund after a tax invoice | Credit note | References the original invoice |
| Refund of an advance with no invoice | Refund voucher | References the receipt voucher |
| Composition or unregistered business | Bill of supply / plain receipt | No GST charged |

The engine supports all six document types from the same template system; which ones a business uses is a setting.

**Numbering**

- Separate consecutive series per document type, per business, per financial year (1 April – 31 March).
- Format: `{prefix}/{FY}/{sequence}`, e.g. `RLR/26-27/0042`; kept within the 16-character limit for invoice numbers (CA to confirm).
- Sequence row locked in the same transaction as the document insert: no gaps, no duplicates; a cancelled invoice keeps its number.

**Tax calculation**

1. Line taxable value = qty × unit price − pro-rated discount (paise).
2. Supplier state = business state code; place of supply = client's state (from billing address; for location-bound services such as on-site shoots the CA confirms whether the event location applies).
3. Same state → CGST and SGST at half the rate each; different state → IGST at the full rate.
4. Tax per line = taxable × rate, rounded half-up to the paise; for CGST/SGST, CGST gets the half rounded down and SGST the remainder, so they always sum to the line tax.
5. Document total = Σ taxable + Σ tax; optional "round off" line to the nearest rupee, shown separately.

**Mandatory fields on every tax document:** supplier name, address, GSTIN; document number and date; client name and address (and GSTIN if registered); place of supply; per line description, HSN/SAC, quantity, taxable value, rate and tax amounts; totals; signature or digital signature field.

## 6. Invoice lifecycle and credit notes

Once issued, an invoice changes only its payment status; any correction to its content is a credit note plus, if needed, a new invoice.

| Status | Meaning | Allowed next |
| --- | --- | --- |
| Draft | Generated, not yet numbered or sent (approval mode) | Issued, discarded |
| Issued | Numbered, PDF stored, sent to client | Partially paid, Paid, Overdue, Cancelled |
| Partially paid | Some payments applied, amount due > 0 | Paid, Overdue, Cancelled |
| Paid | Amount due = 0 | Credited (partly or fully) |
| Overdue | Due date passed, amount due > 0 | Paid, Cancelled |
| Cancelled | Withdrawn by a full credit note | Terminal |

**Rules**

- Drafts carry no number; numbering happens at issue, so discarded drafts leave no gaps.
- Issued invoices are never edited or deleted (no update grant on content columns).
- Wrong amount, wrong client details or cancellation → credit note for the affected value, then a fresh invoice if the sale continues.
- Credit note: own number series; references the original invoice number and date; reason required; PDF generated from the business template.
- Due date: default 7 days after issue (configurable); Overdue triggers balance reminders.

## 7. Cancellation and refunds

Each business sets a cancellation policy as time bands; the system calculates the refund from the band and the owner approves before any money moves.

**Policy format (example default, editable per business)**

| Cancelled before start | Refund of amount paid |
| --- | --- |
| 48 hours or more | 100% |
| 24–48 hours | 50% |
| Under 24 hours or no-show | 0% |
| Business cancels (staff unavailable, etc.) | 100%, always |

**Refund flow**

1. Cancellation confirmed by staff → refund calculated from the policy snapshot stored on the `refunds` row.
2. Owner/admin reviews; may approve, change the amount (reason required) or reject.
3. Approved → Razorpay refund API → status Processing → `refund.processed` webhook → Processed.
4. Documents: credit note if a tax invoice was issued; refund voucher if only a receipt voucher exists.
5. Ledger updated; amount due recalculated; client notified on WhatsApp with the amount and expected timeline.

**Rules**

- Refunds only go back to the original Razorpay payment; never to a different account.
- A refund larger than the amount paid is impossible (validated).
- Failed refund → owner alerted; can retry or record a manual refund with reference.

## 8. Payment collection automation

Reminders run on fixed schedules per payment kind and stop the moment the payment lands, the client opts out, or staff pause them.

| Situation | Automation (defaults) |
| --- | --- |
| Advance link sent, unpaid | Reminder 12 h before expiry; expiry at 24 h; one fresh link after expiry |
| Payment failed | Fresh link once; staff alerted after a second failure |
| Partial payment | Link for the remainder sent immediately |
| Balance due after work | Link at once; reminders day 1, 3, 7; escalation to staff after day 7 |
| Invoice overdue | Same as balance reminders; status Overdue |
| Refund requested | Owner approval request; reminder to owner after 24 h |

All reminder texts are approved WhatsApp utility templates, since most fall outside the 24-hour window.

## 9. Worked example: a Reelrr booking

A car reel shoot with editing, a 5% discount and a 50% advance ends at ₹44,840 total, collected as two payments of ₹22,420; these figures double as a test fixture.

**Quote v2 (accepted)**: client in the same state as Reelrr, so CGST + SGST.

| Line | SAC | Price (₹) | Discount 5% (₹) | Taxable (₹) | CGST 9% (₹) | SGST 9% (₹) | Line total (₹) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Car reel shoot | per business | 30,000 | 1,500 | 28,500 | 2,565 | 2,565 | 33,630 |
| Editing and delivery | per business | 10,000 | 500 | 9,500 | 855 | 855 | 11,210 |
| **Total** |  | **40,000** | **2,000** | **38,000** | **3,420** | **3,420** | **44,840** |

The discount is within the 10% staff limit, so no admin approval is needed. Out of state, the same quote carries IGST 18% = ₹6,840 instead.

**Sequence**

1. v1 sent at ₹40,000 + GST; client asks for a better price → Negotiating.
2. Staff sends v2 with 5% off; client taps Accept → payment plan fixed: advance ₹22,420, balance ₹22,420.
3. Advance link ₹22,420 sent; paid → receipt voucher: taxable ₹19,000 + tax ₹3,420 (if the CA confirms receipt vouchers for advances).
4. Shoot and editing done → tax invoice `RLR/26-27/0042` for ₹44,840, showing ₹22,420 received, ₹22,420 due.
5. Balance link ₹22,420 sent; paid → invoice status Paid; lead Completed; Meta Purchase event value ₹44,840.

**If the client cancels 30 hours before the shoot** (after paying the advance): policy band 24–48 h → 50% refund = ₹11,210; owner approves; Razorpay refund; refund voucher against the receipt voucher.

## 10. Chartered accountant review checklist

These points need a CA's sign-off before the commercial module goes live with paying clients.

- [ ] Documents for an advance on services: receipt voucher vs tax invoice, and timing of the final invoice
- [ ] Place of supply for location-bound services (on-site shoots, home services) when client and event states differ
- [ ] Invoice, receipt voucher and credit note numbering format and length
- [ ] Rounding rule per line and the round-off line
- [ ] CGST/SGST split when the line tax has an odd paisa
- [ ] Documents for composition and unregistered businesses
- [ ] Credit note vs refund voucher for each cancellation case
- [ ] Mandatory fields and signature on generated PDFs
- [ ] Record-keeping period for financial documents
- [ ] E-invoicing (IRN) thresholds that would make a client business ineligible for the MVP
- [ ] GST on Northstar's own subscription invoices to businesses
