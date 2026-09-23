package io.github.fvmeownv.twoshipru;

/** Ошибка с понятным пользователю текстом на русском. Показывается в приложении как есть. */
final class Problem extends Exception {
    Problem(String message) { super(message); }
}
