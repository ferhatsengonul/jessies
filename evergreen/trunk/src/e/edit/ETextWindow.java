package e.edit;

import e.gui.*;
import e.ptextarea.*;
import e.util.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.List;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.Timer;

/**
 * A text-editing component.
 */
public class ETextWindow extends EWindow implements Comparable<ETextWindow>, PTextListener {
    // Used to update the watermark without creating and destroying an excessive number of threads.
    private static final ExecutorService WATERMARK_UPDATE_EXECUTOR = ThreadUtilities.newSingleThreadExecutor("Watermark Updater");
    
    private final String filename;
    private final File file;
    private final PTextArea textArea;
    private final PLineNumberPanel lineNumbers;
    private final JScrollPane scrollPane;
    // Used to display a watermark to indicate such things as a read-only file.
    private final WatermarkViewPort watermarkViewPort;
    private final BirdView birdView;
    private final TagsUpdater tagsUpdater;
    
    private long lastModifiedTime;
    
    // Each text window has its own current regular expression for finds, which may be null if there's no currently active search in that window.
    private String currentRegularExpression;
    
    private Timer findResultsUpdateTimer;
    private PFindListener findResultsUpdater = new PFindListener() {
        public void aboutToFind() {
            // FIXME: isn't this too conservative? shouldn't we check whether the timer is running? if it's not, aren't the results already up to date?
            updateFindResults();
        }
    };
    
    /**
     * Ensures that the TagsPanel is empty if the focused window isn't an ETextWindow.
     */
    static {
        new KeyboardFocusMonitor() {
            public void focusChanged(Component oldOwner, Component newOwner) {
                if (newOwner instanceof EWindow && newOwner instanceof ETextWindow == false) {
                    Evergreen.getInstance().getTagsPanel().ensureTagsAreHidden();
                }
            }
        };
    }
    
    public ETextWindow(String filename) {
        super(filename);
        this.filename = filename;
        this.file = FileUtilities.fileFromString(filename);
        this.textArea = new PTextArea();
        initTextArea();
        initTextAreaPopupMenu();
        
        this.watermarkViewPort = new WatermarkViewPort();
        watermarkViewPort.setView(textArea);
        
        this.scrollPane = new JScrollPane(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setViewport(watermarkViewPort);
        
        this.lineNumbers = new PLineNumberPanel(textArea);
        
        initCaretListener();
        initFocusListener();
        this.birdView = new BirdView(new PTextAreaBirdsEye(textArea), scrollPane.getVerticalScrollBar());
        add(scrollPane, BorderLayout.CENTER);
        add(birdView, BorderLayout.EAST);
        
        this.tagsUpdater = new TagsUpdater(this);
        fillWithContent();
        initUserConfigurableDefaults();
        initFindResultsUpdater();
    }
    
    private void initTextArea() {
        textArea.setPastedTextReformatter(new UnaryFunctor<String, String>() {
            public String evaluate(String pastedText) {
                // Turn non-breakable spaces into normal spaces.
                // These are a problem if, for example, you paste a signature from JavaDoc.
                pastedText = pastedText.replace('\u00a0', ' ');
                // Turn old-style Mac line endings into something sensible.
                // These are a problem if, for example, you paste example code from Apple's on-line documentation.
                pastedText = pastedText.replaceAll("\r\n?", "\n");
                return pastedText;
            }
        });
        
        textArea.addFindListener(findResultsUpdater);
        
        // Disable the default find action so we can offer our own.
        textArea.getActionMap().remove(PActionFactory.makeFindAction().getValue(Action.NAME));
        
        textArea.getTextBuffer().addTextListener(this);
    }
    
    private void initUserConfigurableDefaults() {
        // Since the user can modify these settings, we should only set them
        // from the constructor to avoid reverting the user's configuration.
        // I don't think that even reverting to the saved content should revert
        // configuration changes, until I see a convincing demonstration
        // otherwise.
        // Phil's been bitten by the indentation string reverting while editing
        // TSV files, and I've been repeatedly bitten by a file I want to see
        // in a fixed font reverting to a proportional font each time I save.
        preferencesChanged();
        CharSequence content = textArea.getTextBuffer();
        
        String defaultIndentation = Evergreen.getInstance().getPreferences().getString(EvergreenPreferences.DEFAULT_INDENTATION);
        String indentation = defaultIndentation;
        // IndentationGuesser assumes an environment where everyone's doing
        // their own thing and where our guesses are pretty good. In an
        // environment where there's a coding standard *and* our guesses are
        // often wrong, the guessing is just annoying.
        // FIXME: can we fix IndentationGuesser?
        // FIXME: is this common enough a situation to warrant a public preference?
        if (Parameters.getBoolean("indentation.allowGuessing", true)) {
            indentation = IndentationGuesser.guessIndentationFromFile(content, defaultIndentation);
        }
        textArea.getTextBuffer().putProperty(PTextBuffer.INDENTATION_PROPERTY, indentation);
    }
    
    public void preferencesChanged() {
        Preferences preferences = Evergreen.getInstance().getPreferences();
        initFont();
        textArea.setShouldHideMouseWhenTyping(preferences.getBoolean(EvergreenPreferences.HIDE_MOUSE_WHEN_TYPING));
        //textArea.setBackground(preferences.getColor(EvergreenPreferences.BACKGROUND_COLOR));
        //textArea.setForeground(preferences.getColor(EvergreenPreferences.FOREGROUND_COLOR));
        
        scrollPane.setRowHeaderView(preferences.getBoolean(EvergreenPreferences.SHOW_LINE_NUMBERS) ? lineNumbers : null);
        
        final int defaultMargin = Parameters.getInteger("default.margin", 80);
        final int margin = Parameters.getInteger(getFileType().getName() + ".margin", defaultMargin);
        textArea.showRightHandMarginAt(margin);
        
        repaint();
    }
    
    private void initFont() {
        textArea.setFont(ChangeFontAction.getAppropriateFontForContent(textArea.getTextBuffer()));
    }
    
    public void updateStatusLine() {
        final int selectionStart = textArea.getSelectionStart();
        final int selectionEnd = textArea.getSelectionEnd();
        String message = "";
        if (selectionStart != selectionEnd) {
            // Describe the selected range.
            final int startLineNumber = 1 + textArea.getLineOfOffset(selectionStart);
            final int endLineNumber = 1 + textArea.getLineOfOffset(selectionEnd);
            final int endLineStartOffset = textArea.getLineStartOffset(endLineNumber - 1);
            if (startLineNumber == endLineNumber) {
                message = "Selected " + addressFromOffset(selectionStart, "line ", " columns ") + "-" + (1 + emacsDistance(textArea.getTextBuffer(), selectionEnd, endLineStartOffset));
            } else {
                message = "Selected from line " + startLineNumber + " to " + endLineNumber;
            }
            
            // Work out how many characters and lines are selected.
            final int characterCount = selectionEnd - selectionStart;
            // If we just subtract line numbers, we're effectively counting newlines.
            // That gives a good answer if the user's selected whole lines.
            int lineCount = endLineNumber - startLineNumber;
            // If they've only selected part of the end line, we need to add one because the user will still consider that one of the selected lines.
            // (We get partial selection of the start line for free, because we'll still get its newline, which was counted above.)
            if (selectionEnd != endLineStartOffset) {
                ++lineCount;
            }
            
            // Describe the size of the selection.
            message += " (";
            message += StringUtilities.pluralize(characterCount, "character", "characters");
            if (lineCount > 1) {
                message += " on " + lineCount + " lines";
            }
            message += ")";
        } else {
            // Describe the location of the caret.
            message = "At " + addressFromOffset(selectionStart, "line ", ", column ");
        }
        
        // Show the number of find matches.
        if (currentRegularExpression != null) {
            final int matchCount = textArea.getFindMatchCount();
            message += "; " + StringUtilities.pluralize(matchCount, "match", "matches") + " for \"" + currentRegularExpression + "\"";
        }
        
        Evergreen.getInstance().showStatus(message);
    }
    
    private void initCaretListener() {
        textArea.addCaretListener(new PCaretListener() {
            public void caretMoved(PTextArea textArea, int selectionStart, int selectionEnd) {
                updateStatusLine();
                // BirdView numbers lines from 0.
                final int startLine = textArea.getLineOfOffset(selectionStart);
                final int endLine = textArea.getLineOfOffset(selectionEnd);
                birdView.setSelectedLines(startLine, endLine);
            }
        });
    }
    
    private void initFindResultsUpdater() {
        findResultsUpdateTimer = new Timer(150, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateFindResults();
            }
        });
        findResultsUpdateTimer.setRepeats(false);
    }
    
    private void updateFindResults() {
        FindAction.INSTANCE.repeatLastFind(this);
    }
    
    private void initFocusListener() {
        textArea.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
                updateWatermarkAndTitleBar();
                updateStatusLine();
            }
            
            public void focusLost(FocusEvent e) {
                // We do this when losing rather than gaining focus so that transient gains of focus don't count.
                // For example, if the user switches tab and Swing gives focus to the first text window, that shouldn't count.
                // We want restoreFocusToRememberedTextWindow to restore focus to the text window that lost focus when we last switched away from the new tab.
                rememberWeHadFocusLast();
            }
            
            private void rememberWeHadFocusLast() {
                Workspace workspace = (Workspace) SwingUtilities.getAncestorOfClass(Workspace.class, ETextWindow.this);
                // We might be losing focus because we've just been closed, in which case we're no longer in the component hierarchy.
                if (workspace != null) {
                    workspace.rememberFocusedTextWindow(ETextWindow.this);
                }
            }
        });
    }
    
    public BirdView getBirdView() {
        return birdView;
    }
    
    public String getFilename() {
        return filename;
    }
    
    /** Returns the grep-style ":<line>:<column>" address for the caret position. */
    public String getAddress() {
        String result = addressFromOffset(textArea.getSelectionStart(), ":", ":");
        if (textArea.hasSelection()) {
            // emacs end offsets seem to include the character following.
            result += addressFromOffset(textArea.getSelectionEnd() - 1, ":", ":");
        }
        return result;
    }
    
    private String addressFromOffset(final int offset, final String linePrefix, final String columnPrefix) {
        int lineNumber = 1 + textArea.getLineOfOffset(offset);
        int lineStart = textArea.getLineStartOffset(lineNumber - 1);
        int columnNumber = 1 + emacsDistance(textArea.getTextBuffer(), offset, lineStart);
        return linePrefix + lineNumber + columnPrefix + columnNumber;
    }
    
    public void requestFocus() {
        textArea.requestFocus();
    }
    
    //
    // PTextListener interface.
    //
    
    public void textCompletelyReplaced(PTextEvent e) {
        textBecameDirty();
    }
    
    public void textRemoved(PTextEvent e) {
        textBecameDirty();
    }
    
    public void textInserted(PTextEvent e) {
        textBecameDirty();
    }
    
    private void highlightMergeConflicts() {
        // All merge conflict indications I've ever seen (and I've seen a few) contain this substring:
        final String MERGE_CONFLICT_INDICATOR = "<<<<<<<";
        // And once we're convinced we're looking at a file with merge conflicts, we can accept a more lenient set of dividers.
        // We also match anything after the divider until end of line, because some systems add commentary such as revision numbers and filenames.
        final String ALL_MERGE_CONFLICT_DIVIDERS_REGULAR_EXPRESSION = "(?m)^([<>|=]{7}( .*)?)";
        if (Pattern.compile(MERGE_CONFLICT_INDICATOR).matcher(textArea.getTextBuffer()).find()) {
            FindAction.INSTANCE.findInText(this, ALL_MERGE_CONFLICT_DIVIDERS_REGULAR_EXPRESSION);
        }
    }
    
    public void updateWatermarkAndTitleBar() {
        WATERMARK_UPDATE_EXECUTOR.execute(new Runnable() {
            
            // Fields initialized in "run" so not even that work is done on the EDT.
            private String seriousMessage = null;
            private String nonSeriousMessage = null;
            private boolean hasCounterpart = false;
            
            public void run() {
                updateFileState();
                EventQueue.invokeLater(new Runnable() {
                    public void run() {
                        watermarkViewPort.setWatermark(seriousMessage, nonSeriousMessage);
                        getTitleBar().setShowSwitchButton(hasCounterpart);
                    }
                });
            }
            
            private void updateFileState() {
                if (file.exists()) {
                    if (file.canWrite() == false) {
                        nonSeriousMessage = "(read-only)";
                    }
                    if (isOutOfDateWithRespectToDisk()) {
                        seriousMessage = "(out-of-date)";
                    }
                } else {
                    seriousMessage = "(deleted)";
                }
                hasCounterpart = (getCounterpartFilename() != null);
            }
        });
    }
    
    private void fillWithContent() {
        try {
            lastModifiedTime = file.lastModified();
            textArea.getTextBuffer().readFromFile(file);
            
            configureForGuessedFileType();
            updateWatermarkAndTitleBar();
            highlightMergeConflicts();
            textArea.getTextBuffer().getUndoBuffer().resetUndoBuffer();
            textArea.getTextBuffer().getUndoBuffer().setCurrentStateClean();
            getTitleBar().repaint();
        } catch (Throwable th) {
            Log.warn("in ContentLoader exception handler", th);
            Evergreen.getInstance().showAlert("Couldn't open file \"" + FileUtilities.getUserFriendlyName(file) + "\"", th.getMessage());
            throw new RuntimeException("can't open " + FileUtilities.getUserFriendlyName(file));
        }
    }
    
    private void configureForGuessedFileType() {
        configureForFileType(FileType.guessFileType(filename, textArea.getTextBuffer()));
    }
    
    private int configureCount = 0;
    
    public void configureForFileType(FileType newFileType) {
        // We don't want to waste time if we're already correctly configured.
        // The first time a plain-text file is configured, the text area's file type and the new file type are equal (because plain text is the default).
        // We make sure we still do the right thing for plain text files by keeping count.
        if (configureCount > 0 && newFileType == getFileType()) {
            return;
        }
        ++configureCount;
        
        newFileType.configureTextArea(textArea);
        
        BugDatabaseHighlighter.highlightBugs(textArea);
        
        // Ensure we re-do the tags now we've changed our mind about what kind of tags we're looking for.
        tagsUpdater.updateTags();
        
        repaint();
    }
    
    private void uncheckedRevertToSaved() {
        // FIXME - work with non-empty selection
        int originalCaretPosition = textArea.getSelectionStart();
        fillWithContent();
        int newCaretPosition = Math.min(originalCaretPosition, textArea.getTextBuffer().length());
        textArea.setCaretPosition(newCaretPosition);
        tagsUpdater.updateTags();
        Evergreen.getInstance().showStatus("Reverted to saved version of " + filename);
    }
    
    private boolean showPatchAndAskForConfirmation(String verb, String question, boolean fromDiskToMemory) {
        final Diffable disk = new Diffable("disk at " + FileUtilities.getLastModifiedTime(file), file);
        disk.setFileType(textArea.getFileType());
        final Diffable memory = new Diffable("memory", textArea.getTextBuffer().toString());
        memory.setFileType(textArea.getFileType());
        
        final Diffable from = fromDiskToMemory ? disk : memory;
        final Diffable to = fromDiskToMemory ? memory : disk;
        
        // We reverse the from/to order because we want reverse patches.
        final String title = verb;
        return SimplePatchDialog.showPatchBetween(Evergreen.getInstance().getFrame(), ChangeFontAction.getConfiguredFixedFont(), title, question, verb, to, from);
    }
    
    public boolean canRevertToSaved() {
        return file.exists() && (isDirty() || isOutOfDateWithRespectToDisk());
    }
    
    public void revertToSaved() {
        if (file.exists() == false) {
            Evergreen.getInstance().showAlert("Can't revert to saved", "\"" + getFilename() + "\" does not exist.");
            return;
        }
        if (isDirty() == false && file.exists() && isOutOfDateWithRespectToDisk() == false) {
            Evergreen.getInstance().showAlert("Can't revert to saved", "\"" + getFilename() + "\" is the same on disk as in the editor.");
            return;
        }
        if (showPatchAndAskForConfirmation("Revert to Saved", "Revert to on-disk version of \"" + file.getName() + "\"? (Equivalent to applying the following patch.)", true)) {
            uncheckedRevertToSaved();
        }
    }
    
    @Override public PTextArea getTextArea() {
        return textArea;
    }
    
    @Override
    public void windowWillClose() {
        if (findResultsUpdateTimer != null) {
            findResultsUpdateTimer.stop();
            findResultsUpdateTimer = null;
        }
        Evergreen.getInstance().showStatus("Closed " + filename);
        // FIXME: what else needs doing to ensure that we give back memory?
    }
    
    /**
     * Closes this text window if the text isn't dirty.
     */
    @Override
    public void closeWindow() {
        if (isDirty()) {
            if (showPatchAndAskForConfirmation("Discard Changes", "Discard changes to \"" + file.getName() + "\"? (Equivalent to applying the following patch.)", true) == false) {
                return;
            }
        }
        Evergreen.getInstance().getTagsPanel().ensureTagsAreHidden();
        super.closeWindow();
    }
    
    private void initTextAreaPopupMenu() {
        textArea.getPopupMenu().addMenuItemProvider(new MenuItemProvider() {
            public void provideMenuItems(MouseEvent e, Collection<Action> actions) {
                final List<ExternalToolAction> tools = ExternalTools.getPopUpTools();
                actions.add(new OpenQuicklyAction());
                actions.add(new OpenImportAction());
                actions.add(new FindInFilesAction());
                actions.add(new RevertToSavedAction());
                actions.add(null);
                actions.add(new CheckInChangesAction());
                actions.add(new ShowHistoryAction());
                if (!tools.isEmpty()) {
                    actions.add(null);
                    actions.addAll(tools);
                }
                EPopupMenu.addNumberInfoItems(actions, textArea.getSelectedText());
            }
        });
    }

    /**
     * Returns the filename of the counterpart to this file, or null.
     * A Java .java file, for example, has no counterpart. A C++ .cpp
     * file, on the other hand, may have a .h counterpart (and vice
     * versa).
     */
    public String getCounterpartFilename() {
        // Work out what the counterpart would be called.
        String basename = file.getName().replaceAll("\\.[^.]+$", "");
        
        String parent = file.getParent();
        if (filename.endsWith(".cpp") || filename.endsWith(".cc") || filename.endsWith(".c")) {
            if (FileUtilities.exists(parent, basename + ".hpp")) {
                return basename + ".hpp";
            } else if (FileUtilities.exists(parent, basename + ".hh")) {
                return basename + ".hh";
            } else if (FileUtilities.exists(parent, basename + ".h")) {
                return basename + ".h";
            }
        } else if (filename.endsWith(".hpp") || filename.endsWith(".hh") || filename.endsWith(".h")) {
            if (FileUtilities.exists(parent, basename + ".cpp")) {
                return basename + ".cpp";
            } else if (FileUtilities.exists(parent, basename + ".cc")) {
                    return basename + ".cc";
            } else if (FileUtilities.exists(parent, basename + ".c")) {
                return basename + ".c";
            }
        }
        return null;
    }

    public void switchToCounterpart() {
        String counterpartFilename = getCounterpartFilename();
        if (counterpartFilename != null) {
            Evergreen.getInstance().openFile(getContext() + File.separator + counterpartFilename);
        } else {
            Evergreen.getInstance().showAlert("Can't switch to counterpart", "File \"" + filename + "\" has no counterpart.");
        }
    }

    public FileType getFileType() {
        return textArea.getFileType();
    }
    
    @Override
    public boolean isDirty() {
        return (textArea.getTextBuffer().getUndoBuffer().isClean() == false);
    }
    
    public void textBecameDirty() {
        if (findResultsUpdateTimer != null) {
            findResultsUpdateTimer.restart();
        }
        getTitleBar().repaint();
    }
    
    public boolean isFocusCycleRoot() {
        return true;
    }
    
    /**
     * Returns the offset corresponding to an actual start offset and an emacs line-relative offset.
     * Emacs, it seems, although it allows the user to alter the tab size, talks to the outside world
     * in terms of character cells rather than characters, and takes a tab to be eight cells (and all
     * other characters to be a single cell). So we, who use characters throughout, need to
     * translate emacs offsets into real offsets.
     */
    private static int emacsWalk(CharSequence chars, int offset, int howFar) {
        while (--howFar > 0) {
            char c = chars.charAt(offset);
            if (c == '\t') howFar -= 7;
            offset++;
        }
        return offset;
    }
    
    /**
     * Returns the distance between the given offset and the given line-start offset
     * in emacs' corrupted tabs-are-eight-spaces-and-not-characters-in-their-own-right
     * terms. Damn that program to hell.
     */
    private static int emacsDistance(CharSequence chars, int pureCaretOffset, int lineStart) {
        int result = 0;
        for (int offset = lineStart; offset < pureCaretOffset; offset++) {
            char c = chars.charAt(offset);
            if (c == '\t') result += 7;
            result++;
        }
        return result;
    }
    
    public void jumpToAddress(String address) {
        CharSequence chars = textArea.getTextBuffer();
        StringTokenizer st = new StringTokenizer(address, ":");
        if (st.hasMoreTokens() == false) {
            return;
        }
        int line = Integer.parseInt(st.nextToken()) - 1;
        int offset = textArea.getLineStartOffset(line);
        int maxOffset = textArea.getLineEndOffsetBeforeTerminator(line);
        if (st.hasMoreTokens()) {
            try {
                offset = emacsWalk(chars, offset, Integer.parseInt(st.nextToken()));
            } catch (NumberFormatException ex) {
                ex = ex;
            }
        }
        offset = Math.min(offset, maxOffset);
        
        // We interpret address ending with a ":" (from grep and compilers) as requiring
        // the line to be selected. Other addresses (such as from our open-file-list) just
        // mean "position the caret".
        int endOffset = (address.endsWith(":")) ? (maxOffset + 1) : offset;
        endOffset = Math.min(endOffset, maxOffset);
        
        if (st.hasMoreTokens()) {
            try {
                endOffset = textArea.getLineStartOffset(Integer.parseInt(st.nextToken()) - 1);
            } catch (NumberFormatException ex) {
                ex = ex;
            }
        }
        if (st.hasMoreTokens()) {
            try {
                // emacs end offsets seem to include the character following.
                endOffset = emacsWalk(chars, endOffset, Integer.parseInt(st.nextToken())) + 1;
            } catch (NumberFormatException ex) {
                ex = ex;
            }
        }
        textArea.centerOnNewSelection(offset, endOffset);
    }
    
    /**
    * Returns the name of the context for this window. In this case, the directory the file's in.
    */
    public String getContext() {
        return FileUtilities.getUserFriendlyName(file.getParent());
    }
    
    private boolean isOutOfDateWithRespectToDisk() {
        // If the time stamp on disk is the same as it was when we last read
        // or wrote the file, assume it hasn't changed.
        if (file.lastModified() == lastModifiedTime) {
            return false;
        }
        
        // If the on-disk content is the same as what we have in memory, then
        // the fact that the time stamp is different isn't significant.
        try {
            String currentContentInMemory = textArea.getText();
            String currentContentOnDisk = StringUtilities.readFile(file);
            if (currentContentInMemory.equals(currentContentOnDisk)) {
                lastModifiedTime = file.lastModified();
                return false;
            }
        } catch (Exception ex) {
            Log.warn("Couldn't compare with on-disk copy.", ex);
        }
        
        return true;
    }
    
    /** Saves the text. Returns true if the file was saved okay. */
    public boolean save() {
        Evergreen editor = Evergreen.getInstance();
        
        PTextBuffer buffer = textArea.getTextBuffer();
        String charsetName = (String) buffer.getProperty(PTextBuffer.CHARSET_PROPERTY);
        if (buffer.attemptEncoding(charsetName) == false) {
            Evergreen.getInstance().showAlert("Can't encode file with encoding", "The " + charsetName + " encoding is not capable of representing all characters found in this file. You can change the file's encoding in the File Properties dialog, available from the View menu.");
            return false;
        }
        
        // If the file already exists, check it hasn't changed while we've been editing it.
        try {
            editor.showStatus("Preparing to save " + filename + "...");
            if (file.exists() && isOutOfDateWithRespectToDisk()) {
                if (showPatchAndAskForConfirmation("Overwrite", "Overwrite the currently saved version of \"" + file.getName() + "\"? (Equivalent to applying the following patch.)", false) == false) {
                    return false;
                }
            }
        } finally {
            editor.showStatus("");
        }
        
        // Try to save a backup copy first. Ideally, we should do this from
        // a timer and always have a recent backup around.
        File backupFile = FileUtilities.fileFromString(this.filename + ".bak");
        if (file.exists()) {
            try {
                editor.showStatus("Saving backup copy of " + filename + "...");
                writeToFile(backupFile);
            } catch (Exception ex) {
                editor.showStatus("");
                editor.showAlert("Couldn't save \"" + this.filename + "\"", "Couldn't create backup file: " + ex.getMessage() + ".");
                Log.warn("Problem creating backup file before saving \"" + filename + "\"", ex);
                return false;
            }
        }
        
        try {
            editor.showStatus("Saving " + filename + "...");
            // The file may be a symbolic link on a CIFS server.
            // In this case, it's important that we write into the original file rather than creating a new one.
            writeToFile(file);
            buffer.getUndoBuffer().setCurrentStateClean();
            getTitleBar().repaint();
            editor.showStatus("Saved " + filename);
            backupFile.delete();
            this.lastModifiedTime = file.lastModified();
            configureForGuessedFileType();
            updateWatermarkAndTitleBar();
            tagsUpdater.updateTags();
            SaveMonitor.getInstance().fireSaveListeners();
            return true;
        } catch (Exception ex) {
            editor.showStatus("");
            editor.showAlert("Couldn't save file \"" + filename + "\"", ex.getMessage());
            Log.warn("Problem saving \"" + filename + "\"", ex);
        }
        return false;
    }
    
    private void writeToFile(File file) {
        // Only Java has newline hygiene as part of its language specification, but it probably applies to most computer languages.
        // For now, though, we let authors of plain text do what they like.
        if (getFileType() != FileType.PLAIN_TEXT) {
            // Remember what was selected/where the caret was.
            final int selectionStart = textArea.getSelectionStart();
            final int selectionEnd = textArea.getSelectionEnd();
            
            ensureBufferDoesNotEndInMultipleNewlines();
            ensureBufferEndsInSingleNewline();
            
            // Restore the selection/caret as well as we can.
            final int maxCaretIndex = textArea.getTextBuffer().length();
            textArea.select(Math.min(maxCaretIndex, selectionStart), Math.min(maxCaretIndex, selectionEnd));
            
            // Trim any trailing whitespace, if configured to do so.
            // This code can do a perfect job of maintaining the selection, so it comes outside the cruder code above.
            trimTrailingWhitespace();
        }
        
        textArea.getTextBuffer().writeToFile(file);
    }
    
    private void trimTrailingWhitespace() {
        if (Evergreen.getInstance().getPreferences().getBoolean(EvergreenPreferences.TRIM_TRAILING_WHITESPACE) == false) {
            return;
        }
        PTextBuffer buffer = textArea.getTextBuffer();
        Pattern trailingWhitespacePattern = Pattern.compile("([ \t]+)$", Pattern.MULTILINE);
        if (trailingWhitespacePattern.matcher(buffer).find()) {
            buffer.getUndoBuffer().startCompoundEdit();
            try {
                final PCoordinates selectionStart = textArea.getLogicalCoordinates(textArea.getSelectionStart());
                final PCoordinates selectionEnd = textArea.getLogicalCoordinates(textArea.getSelectionEnd());
                
                Matcher m = trailingWhitespacePattern.matcher(buffer);
                int offset = 0;
                while (m.find(offset)) {
                    textArea.replaceRange("", m.start(), m.end());
                    offset = m.start();
                }
                
                textArea.select(clampedOffsetOf(selectionStart), clampedOffsetOf(selectionEnd));
            } finally {
                buffer.getUndoBuffer().finishCompoundEdit();
            }
        }
    }
    
    private int clampedOffsetOf(PCoordinates position) {
        final int newLineStartOffset = textArea.getLineStartOffset(position.getLineIndex());
        final int newLineLength = textArea.getLineContents(position.getLineIndex()).length();
        final int newOffsetInLine = Math.min(position.getCharOffset(), newLineLength);
        return newLineStartOffset + newOffsetInLine;
    }
    
    /**
     * Compresses multiple newlines at the end of the file down to a single one.
     */
    private void ensureBufferDoesNotEndInMultipleNewlines() {
        PTextBuffer buffer = textArea.getTextBuffer();
        int startIndex = buffer.length();
        int endIndex = buffer.length() - 1;
        // FIXME: should we compress any run of whitespace down to a single '\n'? So "}\n\n  \n\n\n" would be just "}\n"?
        while (startIndex > 0 && buffer.charAt(startIndex - 1) == '\n') {
            --startIndex;
        }
        if (startIndex < endIndex) {
            textArea.replaceRange("", startIndex, endIndex);
        }
    }
    
    /**
     * JLS3 3.4 implies that all files must end with a line terminator. It's
     * pointless requiring a round trip for the user if this isn't the case,
     * so we silently fix their files.
     */
    private void ensureBufferEndsInSingleNewline() {
        PTextBuffer buffer = textArea.getTextBuffer();
        if (buffer.length() == 0 || buffer.charAt(buffer.length() - 1) != '\n') {
            // '\n' is always correct; the buffer will translate if needed when writing to disk.
            textArea.append("\n");
        }
    }
    
    /**
     * Implements the Comparable interface so windows can be sorted
     * into alphabetical order by filename.
     */
    public int compareTo(ETextWindow other) {
        return getFilename().compareTo(other.getFilename());
    }
    
    public void setCurrentRegularExpression(String regularExpression) {
        this.currentRegularExpression = regularExpression;
    }
    
    public String getCurrentRegularExpression() {
        return currentRegularExpression;
    }
}
