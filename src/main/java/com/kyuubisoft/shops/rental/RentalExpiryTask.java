package com.kyuubisoft.shops.rental;

import java.util.logging.Logger;

/**
 * Periodic task that scans the {@link RentalService} slot cache for
 * expired rentals (and, in Phase 2, expired auctions) and runs the
 * relevant finalize path. Wired into the plugin scheduler alongside
 * the existing rent-collection tick.
 */
public class RentalExpiryTask {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");

    private final RentalService rentalService;

    public RentalExpiryTask(RentalService rentalService) {
        this.rentalService = rentalService;
    }

    public void tick() {
        try {
            int expired = rentalService.scanExpired();
            if (expired > 0) {
                LOGGER.info("RentalExpiryTask: finalized " + expired + " expired rental(s)");
            }
        } catch (Exception e) {
            LOGGER.warning("RentalExpiryTask error: " + e.getMessage());
        }
    }
}
