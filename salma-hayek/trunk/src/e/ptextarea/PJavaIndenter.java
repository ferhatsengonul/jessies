package e.ptextarea;

public class PJavaIndenter extends PIndenter {
    /**
     * Returns that part of the given line number that isn't leading/trailing whitespace or comment.
     */
    public String getActivePartOfLine(PTextArea text, int lineNumber) {
        String trimmedLine = text.getLineText(lineNumber).trim();
        return trimmedLine.replaceFirst("//.*", "");
    }
    
    public boolean isElectric(char c) {
        return (c == '}' || c == ':' || c == '#');
    }
    
    public boolean isBlockBegin(String activePartOfLine) {
        return activePartOfLine.endsWith("{");
    }
    public boolean isBlockEnd(String activePartOfLine) {
        return activePartOfLine.startsWith("}");
    }
    public boolean isLabel(String activePartOfLine) {
        return activePartOfLine.matches("(private|public|protected|case|default)\\b.*:");
    }
    
    /**
     * Lines that start with a closing brace or end with a opening brace or a colon tell us
     * definitively what the indentation for the next line should be.
     * Going back this far keeps us tidy in the face of various multi-line comment styles,
     * multi-line C++ output operator expressions and C++ preprocessor commands.
     */
    public boolean isDefinitive(String rawLine) {
        String trimmedLine = rawLine.trim();
        return isBlockBegin(trimmedLine) || isBlockEnd(trimmedLine) || isLabel(trimmedLine);
    }
    
    public int getPreviousDefinitiveLineNumber(PTextArea text, int startLineNumber) {
        for (int lineNumber = startLineNumber - 1; lineNumber >= 0; --lineNumber) {
            String line = text.getLineText(lineNumber);
            if (isDefinitive(line)) {
                return lineNumber;
            }
        }
        return -1;
    }
    
    public String getIndentation(PTextArea text, int lineNumber) {
        String indentation = "";

        int previousDefinitive = getPreviousDefinitiveLineNumber(text, lineNumber);
        if (previousDefinitive != -1) {
            indentation = text.getIndentationOfLine(previousDefinitive);
            
            String activePartOfPrevious = getActivePartOfLine(text, previousDefinitive);
            if (isBlockBegin(activePartOfPrevious) || isLabel(activePartOfPrevious)) {
                indentation = increaseIndentation(text, indentation);
            }
        }
        
        String activePartOfLine = getActivePartOfLine(text, lineNumber);
        if (isBlockEnd(activePartOfLine) || isLabel(activePartOfLine)) {
            indentation = decreaseIndentation(text, indentation);
        }
        // FIXME: this is a pain for Perl/Ruby.
        if (activePartOfLine.startsWith("#")) {
            indentation = "";
        }
        
        // Recognize doc comments, and help out with the ASCII art.
        if (lineNumber > 0) {
            String previousLine = text.getLineText(lineNumber - 1).trim();
            if (previousLine.endsWith("*/")) {
                // Whatever the previous line looks like, if it ends with
                // a close of comment, we're not in a comment, and should
                // do nothing.
            } else if (previousLine.matches("/\\*{1,2}") || previousLine.startsWith("* ")) {
                // We're in a doc comment.
                if (activePartOfLine.startsWith("* ") || activePartOfLine.startsWith("*/")) {
                    // We already have the JavaDoc ASCII art, and just need to
                    // indent it one space.
                    indentation += " ";
                } else {
                    indentation += " * ";
                }
            }
        }
        
        return indentation;
    }
}
