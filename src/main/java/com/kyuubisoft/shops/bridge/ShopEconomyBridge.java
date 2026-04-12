package com.kyuubisoft.shops.bridge;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection-based economy bridge for KS-Shops.
 * Priority: Core's ExternalEconomyBridge -> direct VaultUnlocked vault2 API.
 * If neither is available, all operations return safe defaults (0, false).
 *
 * Instance-based (not static) — ShopPlugin owns the lifecycle.
 */
public class ShopEconomyBridge {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final String PLUGIN_NAME = "KyuubiSoft";

    private enum Backend { CORE, VAULT_DIRECT, NONE }

    private Backend activeBackend = Backend.NONE;
    private boolean initialized = false;

    // Core path: cached ExternalEconomyBridge instance
    private Object coreBridge;

    // Direct VaultUnlocked path: cached economy instance + methods
    private Object vaultEconomy;
    private Method vaultGetBalance;   // getBalance(String, UUID) -> BigDecimal
    private Method vaultWithdraw;     // withdraw(String, UUID, BigDecimal) -> EconomyResponse
    private Method vaultDeposit;      // deposit(String, UUID, BigDecimal) -> EconomyResponse
    private Method vaultHas;          // has(String, UUID, BigDecimal) -> boolean
    private Method vaultFormat;       // format(BigDecimal) -> String
    private Method vaultCurrencyName; // defaultCurrencyNamePlural(String) -> String
    private Method vaultGetName;      // getName() -> String
    private String vaultProviderName;

    // ==================== DETECTION ====================

    /**
     * Detects the available economy backend.
     * Call once during plugin setup.
     */
    public void detect() {
        if (initialized) return;
        initialized = true;

        // 1) Try Core's ExternalEconomyBridge
        if (tryCore()) {
            activeBackend = Backend.CORE;
            LOGGER.info("Economy via Core's ExternalEconomyBridge");
            return;
        }

        // 2) Try VaultUnlocked directly
        if (tryVaultDirect()) {
            activeBackend = Backend.VAULT_DIRECT;
            LOGGER.info("Economy via VaultUnlocked direct (" + vaultProviderName + ")");
            return;
        }

        LOGGER.info("No economy provider detected — shop transactions disabled");
    }

    private boolean tryCore() {
        try {
            Class<?> coreApi = Class.forName("com.kyuubisoft.core.api.CoreAPI");
            Method isAvailable = coreApi.getMethod("isAvailable");
            if (!(Boolean) isAvailable.invoke(null)) return false;

            Class<?> bridgeClass = Class.forName("com.kyuubisoft.core.economy.ExternalEconomyBridge");
            Method getInstance = bridgeClass.getMethod("getInstance");
            Object bridge = getInstance.invoke(null);

            Method isAvailableEco = bridge.getClass().getMethod("isAvailable");
            if (!(Boolean) isAvailableEco.invoke(bridge)) return false;

            coreBridge = bridge;
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.fine("Core economy check failed: " + e.getMessage());
            return false;
        }
    }

    private boolean tryVaultDirect() {
        try {
            Class<?> vaultClass = Class.forName("net.cfh.vault.VaultUnlocked");
            Method economyObjMethod = vaultClass.getMethod("economyObj");
            Object economy = economyObjMethod.invoke(null);
            if (economy == null) return false;

            Class<?> ecoClass = economy.getClass();

            // isEnabled() check
            Method isEnabled = tryResolve(ecoClass, "isEnabled");
            if (isEnabled != null && !(boolean) isEnabled.invoke(economy)) return false;

            // Resolve vault2 API methods
            vaultGetBalance = tryResolve(ecoClass, "getBalance", String.class, UUID.class);
            vaultWithdraw = tryResolve(ecoClass, "withdraw", String.class, UUID.class, BigDecimal.class);
            vaultDeposit = tryResolve(ecoClass, "deposit", String.class, UUID.class, BigDecimal.class);
            vaultHas = tryResolve(ecoClass, "has", String.class, UUID.class, BigDecimal.class);
            vaultFormat = tryResolve(ecoClass, "format", BigDecimal.class);
            vaultCurrencyName = tryResolve(ecoClass, "defaultCurrencyNamePlural", String.class);
            if (vaultCurrencyName == null) {
                vaultCurrencyName = tryResolve(ecoClass, "currencyNamePlural");
            }
            vaultGetName = tryResolve(ecoClass, "getName");

            // Minimum: getBalance + withdraw + deposit required for shops
            if (vaultGetBalance == null || vaultWithdraw == null || vaultDeposit == null) {
                LOGGER.warning("VaultUnlocked missing required methods (getBalance/withdraw/deposit)");
                return false;
            }

            vaultEconomy = economy;

            // Cache provider name
            if (vaultGetName != null) {
                try { vaultProviderName = (String) vaultGetName.invoke(economy); }
                catch (Exception e) { vaultProviderName = "Unknown"; }
            } else {
                vaultProviderName = ecoClass.getSimpleName();
            }

            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.warning("VaultUnlocked direct init failed: " + e.getMessage());
            return false;
        }
    }

    private static Method tryResolve(Class<?> clazz, String name, Class<?>... params) {
        try { return clazz.getMethod(name, params); }
        catch (NoSuchMethodException e) { return null; }
    }

    private static boolean extractTransactionSuccess(Object response) {
        try {
            Method m = response.getClass().getMethod("transactionSuccess");
            return (boolean) m.invoke(response);
        } catch (Exception e) {
            if (response instanceof Boolean b) return b;
            return false;
        }
    }

    // ==================== PUBLIC API ====================

    public boolean isAvailable() {
        return activeBackend != Backend.NONE;
    }

    public double getBalance(UUID playerUuid) {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("getBalance", UUID.class);
                return (Double) m.invoke(coreBridge, playerUuid);
            }
            if (activeBackend == Backend.VAULT_DIRECT) {
                BigDecimal result = (BigDecimal) vaultGetBalance.invoke(vaultEconomy, PLUGIN_NAME, playerUuid);
                return result != null ? result.doubleValue() : 0;
            }
        } catch (Exception e) {
            LOGGER.warning("Economy getBalance failed: " + e.getMessage());
        }
        return 0;
    }

    public boolean has(UUID playerUuid, double amount) {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("has", UUID.class, double.class);
                return (Boolean) m.invoke(coreBridge, playerUuid, amount);
            }
            if (activeBackend == Backend.VAULT_DIRECT) {
                if (vaultHas != null) {
                    return (boolean) vaultHas.invoke(vaultEconomy, PLUGIN_NAME, playerUuid, BigDecimal.valueOf(amount));
                }
                return getBalance(playerUuid) >= amount;
            }
        } catch (Exception e) {
            LOGGER.warning("Economy has failed: " + e.getMessage());
        }
        return false;
    }

    public boolean withdraw(UUID playerUuid, double amount) {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("withdraw", UUID.class, double.class);
                return (Boolean) m.invoke(coreBridge, playerUuid, amount);
            }
            if (activeBackend == Backend.VAULT_DIRECT) {
                Object response = vaultWithdraw.invoke(vaultEconomy, PLUGIN_NAME, playerUuid, BigDecimal.valueOf(amount));
                return extractTransactionSuccess(response);
            }
        } catch (Exception e) {
            LOGGER.warning("Economy withdraw failed: " + e.getMessage());
        }
        return false;
    }

    public boolean deposit(UUID playerUuid, double amount) {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("deposit", UUID.class, double.class);
                return (Boolean) m.invoke(coreBridge, playerUuid, amount);
            }
            if (activeBackend == Backend.VAULT_DIRECT) {
                Object response = vaultDeposit.invoke(vaultEconomy, PLUGIN_NAME, playerUuid, BigDecimal.valueOf(amount));
                return extractTransactionSuccess(response);
            }
        } catch (Exception e) {
            LOGGER.warning("Economy deposit failed: " + e.getMessage());
        }
        return false;
    }

    public String format(double amount) {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("format", double.class);
                return (String) m.invoke(coreBridge, amount);
            }
            if (activeBackend == Backend.VAULT_DIRECT && vaultFormat != null) {
                return (String) vaultFormat.invoke(vaultEconomy, BigDecimal.valueOf(amount));
            }
        } catch (Exception e) {
            LOGGER.warning("Economy format failed: " + e.getMessage());
        }
        return String.format("%.0f", amount);
    }

    public String getCurrencyName() {
        try {
            if (activeBackend == Backend.CORE) {
                Method m = coreBridge.getClass().getMethod("getCurrencyName");
                String name = (String) m.invoke(coreBridge);
                return name != null ? name : "Gold";
            }
            if (activeBackend == Backend.VAULT_DIRECT && vaultCurrencyName != null) {
                String name;
                if (vaultCurrencyName.getParameterCount() == 1) {
                    name = (String) vaultCurrencyName.invoke(vaultEconomy, PLUGIN_NAME);
                } else {
                    name = (String) vaultCurrencyName.invoke(vaultEconomy);
                }
                return name != null ? name : "Gold";
            }
        } catch (Exception e) {
            LOGGER.warning("Economy getCurrencyName failed: " + e.getMessage());
        }
        return "Gold";
    }

    /**
     * Resets all cached state. Used for reload scenarios.
     */
    public void reset() {
        activeBackend = Backend.NONE;
        initialized = false;
        coreBridge = null;
        vaultEconomy = null;
        vaultGetBalance = null;
        vaultWithdraw = null;
        vaultDeposit = null;
        vaultHas = null;
        vaultFormat = null;
        vaultCurrencyName = null;
        vaultGetName = null;
        vaultProviderName = null;
    }
}
