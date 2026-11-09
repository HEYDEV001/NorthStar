package com.northstar.tenancy.internal;

/** The answer to POST /api/v1/businesses: the new business, and the caller's role in it (always admin). */
record CreatedBusinessResponse(BusinessView business, String role) {}
