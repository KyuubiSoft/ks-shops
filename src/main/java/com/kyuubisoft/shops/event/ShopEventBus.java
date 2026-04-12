package com.kyuubisoft.shops.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Simple thread-safe event bus for Shop custom events.
 * Singleton — other mods can register listeners to react to shop activity.
 *
 * Usage from other mods:
 * <pre>
 * ShopEventBus.getInstance().register(ShopTransactionEvent.class, event -> {
 *     // React to transaction...
 * });
 * </pre>
 */
public class ShopEventBus {

    private static final Logger LOGGER = Logger.getLogger("KyuubiSoft Shops");
    private static final ShopEventBus INSTANCE = new ShopEventBus();

    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public static ShopEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Register a listener for a specific event type.
     */
    @SuppressWarnings("unchecked")
    public <T> void register(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Fire an event, notifying all registered listeners.
     */
    @SuppressWarnings("unchecked")
    public <T> void fire(T event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list == null || list.isEmpty()) return;

        for (Consumer<?> consumer : list) {
            try {
                ((Consumer<T>) consumer).accept(event);
            } catch (Exception e) {
                LOGGER.warning("Shop event listener error for "
                    + event.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Remove all listeners (e.g. on plugin shutdown).
     */
    public void clear() {
        listeners.clear();
    }
}
