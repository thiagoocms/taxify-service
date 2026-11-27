package br.com.cshub.taxify.integration.fiscalxml;

import java.util.function.Supplier;

/**
 * The purpose of the provider calls made by the current thread (the provider clients are synchronous: the call runs on
 * the caller's thread). A scope replaces the outer one and puts it back when it ends; a {@link #priority} scope wins
 * over every scope inside it - all calls of a "Sincronizar agora" attempt count as {@link ProviderCallPurpose#PRIORITY}.
 * No scope = {@link ProviderCallPurpose#MANUAL}, unless the client tells a count from a page by itself.
 */
public final class ProviderCallContext {

    private static final ThreadLocal<ProviderCallPurpose> PURPOSE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PRIORITY = new ThreadLocal<>();

    private ProviderCallContext() {
    }

    public static <T> T call(ProviderCallPurpose purpose, Supplier<T> body) {
        ProviderCallPurpose outer = PURPOSE.get();
        PURPOSE.set(purpose);
        try {
            return body.get();
        } finally {
            restore(PURPOSE, outer);
        }
    }

    public static void run(ProviderCallPurpose purpose, Runnable body) {
        call(purpose, () -> {
            body.run();
            return null;
        });
    }

    /** Every call inside, whatever its stage, is a priority call when {@code priority} is true. */
    public static void priority(boolean priority, Runnable body) {
        if (!priority) {
            body.run();
            return;
        }
        Boolean outer = PRIORITY.get();
        PRIORITY.set(Boolean.TRUE);
        try {
            body.run();
        } finally {
            restore(PRIORITY, outer);
        }
    }

    /** The current purpose; null = no scope (the client decides from the kind of call). */
    public static ProviderCallPurpose current() {
        if (Boolean.TRUE.equals(PRIORITY.get())) {
            return ProviderCallPurpose.PRIORITY;
        }
        return PURPOSE.get();
    }

    private static <T> void restore(ThreadLocal<T> holder, T outer) {
        if (outer == null) {
            holder.remove();
        } else {
            holder.set(outer);
        }
    }
}
