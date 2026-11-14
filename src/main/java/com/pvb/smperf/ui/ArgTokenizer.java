package com.pvb.smperf.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Shell-style splitting for the Arguments box. Each line is split on whitespace;
 * '...' and "..." keep spaces inside one argument; a backslash only escapes a
 * following space, ' or " (so Windows paths like C:\data\in.txt stay intact).
 */
public final class ArgTokenizer {

    private ArgTokenizer() {}

    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int lineNo = 0;
        for (String line : text.split("\\R")) {
            lineNo++;
            try {
                out.addAll(tokenize(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Arguments, line " + lineNo + ": " + e.getMessage());
            }
        }
        return out;
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;   // so "" still produces an empty argument
        char quote = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && i + 1 < s.length() && " \"'".indexOf(s.charAt(i + 1)) >= 0) {
                cur.append(s.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0)
            throw new IllegalArgumentException("unclosed " + (quote == '"' ? "double" : "single") + " quote");
        if (inToken) out.add(cur.toString());
        return out;
    }
}