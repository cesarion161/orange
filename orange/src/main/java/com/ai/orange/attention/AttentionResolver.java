package com.ai.orange.attention;

import java.util.Set;

/**
 * What the user's answer to an item of one of {@link #kinds()} does (remote-first spec §7.2, §7.5): a
 * plan's approve, drop or note, a stuck task's answer. {@link AttentionService#resolve} calls it in its
 * transaction, before the item is marked resolved, so what the answer does and the item's resolution
 * commit together.
 */
public interface AttentionResolver {

    /** The item kinds whose answers this resolver acts on. */
    Set<String> kinds();

    /**
     * Acts on the user's answer to {@code item}, which is not resolved yet.
     *
     * @throws IllegalArgumentException to refuse the answer, saying what to answer instead; the item then
     *                                  stays as it was
     */
    void onResolve(AttentionItem item, String answer);
}
