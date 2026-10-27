package com.elfmcys.ysm.model.service;

/** Whole-path, case-sensitive matching over Unicode code points; only * and ? are special. */
public final class CatalogGlob {
    private CatalogGlob() {}

    public static boolean matches(String pattern, String path) {
        var tokens = pattern.codePoints().toArray();
        var input = path.codePoints().toArray();
        int p = 0, i = 0, star = -1, retry = 0;
        while (i < input.length) {
            if (p < tokens.length && (tokens[p] == '?' || tokens[p] == input[i])) {
                p++;
                i++;
            } else if (p < tokens.length && tokens[p] == '*') {
                star = p++;
                retry = i;
            } else if (star >= 0) {
                p = star + 1;
                i = ++retry;
            } else
                return false;
        }
        while (p < tokens.length && tokens[p] == '*')
            p++;
        return p == tokens.length;
    }
}
