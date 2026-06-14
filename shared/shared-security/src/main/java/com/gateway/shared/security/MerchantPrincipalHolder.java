package com.gateway.shared.security;

public final class MerchantPrincipalHolder {

    static final String REQUEST_ATTR = "merchant.principal";

    private static final ThreadLocal<MerchantPrincipal> HOLDER = new ThreadLocal<>();

    private MerchantPrincipalHolder() {}

    public static void set(MerchantPrincipal principal) {
        HOLDER.set(principal);
    }

    public static MerchantPrincipal current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
