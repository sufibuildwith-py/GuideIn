package io.guidein.graph.application;

/** Conservative lexical depth preflight protects the recursive AST parser from hostile nesting. */
final class JavaSyntaxBudget {
    private JavaSyntaxBudget() {}
    static boolean within(String source, int maxDepth) {
        int[] depths = new int[4]; char quote = 0; boolean lineComment = false, blockComment = false, textBlock = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n' || c == '\r') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (textBlock) {
                if (c == '\\') { i++; continue; }
                if (source.startsWith("\"\"\"", i)) { textBlock = false; i += 2; } continue;
            }
            if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (source.startsWith("\"\"\"", i)) { textBlock = true; i += 2; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            // Unicode escapes are processed before lexical analysis by Java. Decline them rather than undercount hidden delimiters.
            if (c == '\\' && next == 'u') return false;
            int open = "([{<".indexOf(c), close = ")]} >".replace(" ", "").indexOf(c);
            if (open >= 0) { if (++depths[open] > maxDepth) return false; }
            else if (close >= 0) depths[close] = Math.max(0, depths[close] - 1);
        }
        return true;
    }
}
