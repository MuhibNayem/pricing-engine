package com.saas.pricing.core.model.invoice;

/**
 * How invoice numbers are sequenced.
 *
 * <p>Not a matter of taste. All EU member states and the UK require invoices to be numbered
 * <em>sequentially across the business</em>; the rest of the world generally does not, and many
 * merchants prefer per-customer sequences so a competitor cannot infer their volume from a
 * customer's statement.
 *
 * <p>Both are offered because both are legitimate, and the choice is a legal/tax decision the
 * merchant makes — not one a library should make for them.
 */
public enum InvoiceNumberScheme {

    /**
     * One sequence for the whole tenant: {@code INV-0001}, {@code INV-0002}, ...
     *
     * <p>The default where it is legally required, because a per-customer series cannot prove that
     * no invoice was omitted.
     */
    ACCOUNT_SEQUENTIAL,

    /**
     * One sequence per customer, each behind that customer's own prefix: {@code ACME-0001},
     * {@code ACME-0002}, {@code GLOBEX-0001}.
     *
     * <p>Hides total volume from a customer. Every customer needs a distinct prefix; a customer
     * without one is a configuration error and is refused rather than silently given the account
     * prefix, which would put two customers in one sequence and break both.
     */
    CUSTOMER_SEQUENTIAL
}