# PSD2 and PSD3 compliance

This page maps the requirements of the second Payment Services Directive (PSD2, Directive (EU) 2015/2366) and its
Regulatory Technical Standards on strong customer authentication (the SCA RTS, Delegated Regulation (EU) 2018/389)
to the behaviour of the [card payment template](cardpayments.md). It also notes where the PSD3 package (the third
Payment Services Directive and the Payment Services Regulation, PSR) changes these requirements.

!!! note
    The template is a building block, not a payment service provider. Compliance is the responsibility of whoever
    operates a service built on it. This page shows which obligations the template's model meets for you, which ones
    it only supports, and which ones the operator has to cover. It is not legal advice. Check the PSD3 and PSR
    references against the final texts as published in the Official Journal.

## Roles

The template is written for the payee side of a card payment: an acquirer, or a processor working on behalf of one.

* **Merchants** are the payees. A merchant can be onboarded either directly or through an aggregator (a payment
  facilitator, or *super merchant*) that submits payments for sub-merchants.
* **The authentication provider** carries out strong customer authentication of the payer. It is an outbox request
  (`Authentication`) that the template does not implement itself.
* **The acquirer** authorises, captures and refunds the payment, and reconciles settlement batches.

Most PSD2 obligations fall on the payer's payment service provider (the issuer). The sections below cover the ones
that apply to the payee's side, and the payee-side support the issuer's obligations depend on.

## Strong customer authentication

| Requirement                                                                           | How the template handles it                                                                                                                                                                                                                                                                                                                                                                                         |
|---------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| SCA when the payer initiates an electronic payment (PSD2 Art. 97(1)(b))                | Every payment request goes through `ProcessingAuthentication` before the acquirer is involved. Authorisation and pre-authorisation can only be triggered by a successful authentication result. No transition goes from `Begin` straight to `ProcessingAuthorisation`.                                                                                                                                               |
| Failed authentication must not lead to a payment (SCA RTS Art. 4)                      | Every authentication failure (`InvalidAuthentication`, `InvalidAuthenticationToken`, `InvalidPaymentTokenOwnership`, `InvalidPaymentTokenStatus`, `AuthenticationUnavailable`, or an unknown status) ends in the terminal state `AuthenticationFailed`. The request is completed as invalid (HTTP 400), except when the authenticator is unavailable or returns an unknown status, which is a server-side failure (HTTP 500). For invalid authentication and invalid token ownership, the acquirer is also notified (`FailedAuthentication`). |
| Dynamic linking for remote transactions (PSD2 Art. 97(2), SCA RTS Art. 5)              | The authentication provider receives the complete original payment request (`AuthenticationData.paymentRequest`), including amount and merchant, so it can check that the authentication was made for this amount and this payee. The cryptogram it returns (`AuthenticationResult.cryptogram`) is sent to the acquirer with the authorisation. The template itself does not verify the link; that is up to the authentication provider and the issuer. |
| Amount integrity                                                                       | The amount is validated before authentication (`ValidatedAmount`): the purchase amount must be present, purchase plus cashback must equal the requested amount, and cashback is only allowed for in-store payments with immediate capture. Captures and refunds can never exceed what was authenticated and authorised (see [Amount not known in advance](#amount-not-known-in-advance)).                                 |
| Payee's PSP must accept SCA (PSD2 Art. 74(2))                                          | The template never sends an authorisation without an authentication result, so the payee side cannot "fail to accept" SCA and become liable under Art. 74(2).                                                                                                                                                                                                                                                         |
| SCA exemptions (SCA RTS Art. 10–18) and merchant-initiated transactions                 | **Not supported.** All payments are authenticated. Exemptions such as low-value contactless (Art. 11), unattended terminals (Art. 12), recurring payments (Art. 14), low-value remote payments (Art. 16) and transaction risk analysis (Art. 18), as well as merchant-initiated transactions, would need new transitions that skip or flag authentication.                                                              |

## Amount not known in advance

PSD2 Art. 75 says that when the amount of a card payment is not known in advance, funds may only be blocked if the
payer has agreed to the exact amount, and must be released without undue delay once the exact amount is known.

The template's pre-authorisation flow (`capture = false`) models this:

* The payer authenticates the amount in the payment request, and that amount is what gets pre-authorised.
* Captures (`CaptureRequest`) are allowed in `Preauthorised` and `Authorised`. A capture that would bring the total
  captured above the pre-authorised amount is declined (`DeclinedUnauthorisedCapture`).
* A capture requested 7 days or more after the transaction time is declined (`DeclineLateCapture`) and the acquirer
  is notified (`CaptureRequestedTooLate`).
* A pre-authorisation can be cancelled while it is still in `Preauthorised` (`Cancel`), which reverses it at the
  acquirer and notifies the merchant.

**Gaps:**

* Nothing releases an unused pre-authorisation automatically. `Preauthorised` has no timeout, so a pre-authorisation
  that is neither captured nor cancelled keeps the funds blocked until the issuer lets it expire.
* After a partial capture the remaining amount is not released. The payment moves to `Authorised` and the difference
  stays blocked.

The PSR is expected to make these rules stricter, requiring the blocked amount to be proportionate to the final amount
that can reasonably be expected. Operators should set a sensible pre-authorisation amount at the merchant integration
and, to meet "without undue delay", add a timeout on `Preauthorised` and a release after the final capture.

## Consent, revocation and reversals

* **Consent (PSD2 Art. 64).** The authenticated payment request is the payer's consent. It is stored as the first
  event (`PaymentRequest`) in the payment's event log, together with the raw request bytes.
* **Irrevocability (PSD2 Art. 80).** The template has no transition that lets the payer revoke a payment once it has
  been authorised. Merchant-initiated cancellation is only possible before capture (`Cancel` in `Preauthorised`).
* **Technical reversals.** If the acquirer does not respond in time (`ProcessingAuthorisation` and `ProcessingRefund`
  roll back after 6.6 seconds), the request is reversed at the acquirer and the merchant is notified. A payment
  whose outcome is unknown can therefore not end up silently authorised. Every reversal records whether it was
  client-originated and whether it was technical (`ReversalData`).
* **Refunds.** Refunds are limited to the captured amount minus previous refunds (`DeclinedRefund` otherwise).

## Payee identification

To let payers recognise transactions on their statements, the payee has to be clearly identified in the payment
data. The PSR puts more emphasis on this than PSD2 did.

* Every payment is tied to a registered merchant (`Aggregate.Merchant`) that has a display name, location and
  merchant category code. Payments for unknown merchants are rejected (`UnknownMerchant`).
* Payments for suspended or deleted merchants are rejected (`InactiveMerchant`).
* An aggregator can only submit payments for its own merchants (`IllegalMerchant`).
* For a super merchant, the sub-merchant's display name, category code and city are required on every payment
  (`InsufficientMerchantDetails`), and these details, not the aggregator's, are sent to the acquirer.

## Evidence and traceability

PSD2 Art. 72 puts the burden of proof on the payment service provider: it must show that a payment was
authenticated, accurately recorded and not affected by a technical breakdown. The SCA RTS (Art. 2 and 3) require
transaction monitoring and regular review of security measures.

Because the state machine is event sourced, every payment has a complete, append-only history: the original
request, the authentication result (reference, provider, cryptogram), every message to and response from the
acquirer, and every reversal, capture and refund, all with timestamps. Outbox requests are separate entities with
their own logs, so each request and its retries can be traced. Settlement batches are reconciled against the
acquirer's totals, and a mismatch moves the batch to `Error` (`OutOfBalance`).

The template does **not** perform transaction monitoring or fraud detection. Those must be provided by the operator,
the authentication provider or the issuer.

## Security of payment data

* The payment token (PAN, PSN and expiry date) is received encrypted inside the authentication data, and is
  decrypted by an injected `tokenDecrypter` only when an outgoing message to the acquirer is built.
* The transaction time must be within 15 minutes of the server time (`ValidatedTransactionTime`), which limits the
  window for replaying old requests.

**Gap:** The decrypted `PaymentToken` is part of the input of the acquirer outbox requests (`Authorisation`,
`Preauthorisation`, `Capture`, `Refund`, `FailedAuthentication`, `CaptureRequestedTooLate`), and outbox input is
stored in the outbox entity's event log. This means card data is stored unencrypted in the database. Operators must
encrypt this data at rest, or change the template so that the token is decrypted inside the message creator and
never stored.

Operational and security risk management, incident reporting (PSD2 Art. 95–96, which for most PSPs now falls under
DORA) and data protection (PSD2 Art. 94) are the operator's responsibility.

## Summary of gaps

| Gap                                                     | Related requirement               |
|---------------------------------------------------------|-----------------------------------|
| No SCA exemptions or merchant-initiated transactions    | SCA RTS Art. 10–18                |
| Unused pre-authorisations are not released              | PSD2 Art. 75, PSR                 |
| Remainder after partial capture is not released         | PSD2 Art. 75, PSR                 |
| Suspended or deleted merchants are not cleanly rejected | Payee identification              |
| Decrypted card data is stored in outbox event logs      | PSD2 Art. 95, PCI DSS             |
| No transaction monitoring                               | SCA RTS Art. 2                    |
