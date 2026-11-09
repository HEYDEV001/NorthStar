package com.northstar.tenancy.internal;

/** The signed-in user's own profile, as they just saved it. Their own phone is shown in full to them only. */
record ProfileView(String fullName, String phone) {}
