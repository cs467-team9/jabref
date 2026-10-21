package org.jabref.gui.entryeditor.citationrelationtab;

import java.util.Comparator;
import java.util.List;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import org.jabref.gui.DialogService;
import org.jabref.gui.LibraryTab;
import org.jabref.gui.StateManager;
import org.jabref.gui.autocompleter.SuggestionProviders;
import org.jabref.gui.fieldeditors.FieldEditorFX;
import org.jabref.gui.fieldeditors.FieldEditors;
import org.jabref.gui.fieldeditors.FieldNameLabel;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.gui.preview.PreviewPanel;
import org.jabref.gui.undo.JabRefGuiUndoManager;
import org.jabref.gui.undo.RedoAction;
import org.jabref.gui.undo.UndoAction;
import org.jabref.gui.util.BaseDialog;
import org.jabref.gui.util.FieldsUtil;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.logic.journals.JournalAbbreviationRepository;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryType;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;
import org.jabref.model.entry.types.EntryType;

import com.airhacks.afterburner.injection.Injector;
import org.jspecify.annotations.NullMarked;

/// Shows one entry with its type and fields editable, so that the
/// metadata can be corrected before the entry becomes part of the library.
///
/// The fields use the same editors as the entry editor, so that, for example, the DOI editor offers
/// to fetch the bibliographic data. The editors write to the entry directly; the caller hands in a copy
/// so that [Decision#SKIP] and [Decision#STOP] leave the fetched entry as it was.
@NullMarked
public class CitationEntryReviewDialog extends BaseDialog<CitationEntryReviewDialog.Decision> {

    /// The user's choice for the entry currently under review.
    public enum Decision {
        /// Keep the edits and add this entry to the library.
        ADD,
        /// Leave this entry out and continue with the next one.
        SKIP,
        /// End the review. Entries accepted before this one are still added.
        STOP
    }

    private static final double LABEL_COLUMN_MIN_WIDTH = 110;
    private static final double EDITOR_COLUMN_MIN_WIDTH = 360;
    private static final double ROW_HEIGHT = 32;
    private static final double MULTILINE_ROW_HEIGHT = 96;
    private static final double FIELDS_DIVIDER_POSITION = 0.55;
    private static final double DIALOG_PREF_WIDTH = 1100;
    private static final double DIALOG_PREF_HEIGHT = 650;

    private final BibEntry entry;
    private final ComboBox<EntryType> entryTypeCombo = new ComboBox<>();

    /// The entry is not part of the library yet, so its edits are kept out of the library's undo history.
    private final JabRefGuiUndoManager reviewUndoManager = new JabRefGuiUndoManager();

    /// Function to build the review dialog for one fetched entry
    ///
    /// @param entry             copy of the fetched entry, edited in place
    /// @param position          one-based number of this entry within the review
    /// @param total             number of entries to review
    /// @param databaseContext   library the entry is reviewed for
    /// @param entryTypesManager source of the selectable entry types
    public CitationEntryReviewDialog(BibEntry entry,
                                     int position,
                                     int total,
                                     BibDatabaseContext databaseContext,
                                     BibEntryTypesManager entryTypesManager,
                                     GuiPreferences preferences,
                                     StateManager stateManager,
                                     DialogService dialogService,
                                     TaskExecutor taskExecutor) {
        this.entry = entry;

        setTitle(Localization.lang("Review entry %0 of %1", position, total));

        ButtonType addButtonType = new ButtonType(Localization.lang("Add to library"), ButtonBar.ButtonData.OK_DONE);
        ButtonType skipButtonType = new ButtonType(Localization.lang("Skip entry"), ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType stopButtonType = new ButtonType(Localization.lang("Stop review"), ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().setAll(addButtonType, skipButtonType, stopButtonType);

        setUpEntryTypeCombo(databaseContext, entryTypesManager);
        getDialogPane().setContent(createContent(databaseContext, preferences, stateManager, dialogService, taskExecutor));
        getDialogPane().setPrefSize(DIALOG_PREF_WIDTH, DIALOG_PREF_HEIGHT);

        setResultConverter(buttonType -> {
            if (addButtonType.equals(buttonType)) {
                entry.setType(entryTypeCombo.getValue());
                return Decision.ADD;
            }
            if (skipButtonType.equals(buttonType)) {
                return Decision.SKIP;
            }
            return Decision.STOP;
        });
    }

    /// Function to fill the entry type combo box with the types of the library's mode
    ///
    /// @param databaseContext   library deciding which entry types are offered
    /// @param entryTypesManager source of the selectable entry types
    private void setUpEntryTypeCombo(BibDatabaseContext databaseContext, BibEntryTypesManager entryTypesManager) {
        entryTypeCombo.getItems().setAll(entryTypesManager.getAllTypes(databaseContext.getMode()).stream()
                                                          .map(BibEntryType::getType)
                                                          .sorted(Comparator.comparing(EntryType::getDisplayName))
                                                          .toList());
        new ViewModelListCellFactory<EntryType>()
                .withText(EntryType::getDisplayName)
                .install(entryTypeCombo);
        entryTypeCombo.setValue(entry.getType());
        entryTypeCombo.setMaxWidth(Double.MAX_VALUE);
    }

    /// Function to build the dialog body: the entry summary above the editable type and field rows
    private VBox createContent(BibDatabaseContext databaseContext,
                               GuiPreferences preferences,
                               StateManager stateManager,
                               DialogService dialogService,
                               TaskExecutor taskExecutor) {
        Label summary = new Label(entry.getAuthorTitleYear());
        summary.setWrapText(true);

        GridPane fieldGrid = new GridPane();
        fieldGrid.setHgap(10);
        fieldGrid.setVgap(6);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(LABEL_COLUMN_MIN_WIDTH);
        labelColumn.setHalignment(HPos.RIGHT);
        ColumnConstraints editorColumn = new ColumnConstraints();
        editorColumn.setMinWidth(EDITOR_COLUMN_MIN_WIDTH);
        editorColumn.setHgrow(Priority.ALWAYS);
        fieldGrid.getColumnConstraints().setAll(labelColumn, editorColumn);

        fieldGrid.add(new Label(Localization.lang("Entry type")), 0, 0);
        fieldGrid.add(entryTypeCombo, 1, 0);
        fieldGrid.getRowConstraints().add(new RowConstraints());

        JournalAbbreviationRepository journalAbbreviationRepository = Injector.instantiateModelOrService(JournalAbbreviationRepository.class);
        SuggestionProviders suggestionProviders = stateManager.activeTabProperty().get()
                                                              .map(LibraryTab::getSuggestionProviders)
                                                              .orElse(new SuggestionProviders());
        UndoAction undoAction = createUndoAction(dialogService, stateManager);
        RedoAction redoAction = createRedoAction(dialogService, stateManager);

        int row = 1;
        for (Field field : editableFields()) {
            FieldEditorFX fieldEditor = FieldEditors.getForField(
                    field,
                    journalAbbreviationRepository,
                    preferences,
                    databaseContext,
                    entry.getType(),
                    suggestionProviders,
                    reviewUndoManager,
                    undoAction,
                    redoAction);
            fieldEditor.bindToEntry(entry);
            fieldGrid.add(new FieldNameLabel(field), 0, row);
            fieldGrid.add(fieldEditor.getNode(), 1, row);
            fieldGrid.getRowConstraints().add(createFieldRowConstraints(field, fieldEditor));
            row++;
        }

        ScrollPane scrollPane = new ScrollPane(fieldGrid);
        scrollPane.setFitToWidth(true);

        // The preview follows the edits, as next to the entry editor's fields
        PreviewPanel previewPanel = new PreviewPanel(dialogService, preferences.getKeyBindingRepository(), preferences, taskExecutor, stateManager);
        previewPanel.setDatabase(databaseContext);
        previewPanel.setEntry(entry);

        SplitPane fieldsAndPreview = new SplitPane(scrollPane, previewPanel);
        fieldsAndPreview.setDividerPositions(FIELDS_DIVIDER_POSITION);
        VBox.setVgrow(fieldsAndPreview, Priority.ALWAYS);

        VBox content = new VBox(10, summary, fieldsAndPreview);
        content.setPadding(new Insets(10));
        return content;
    }

    /// Function to give a field row a fixed height
    ///
    /// The field editors ask for unlimited height and rely on their container to limit it, as the entry editor does
    /// with its row constraints. Rows sized to their content would grow without end, so each row gets its height here.
    ///
    /// @param field       field shown in the row
    /// @param fieldEditor editor shown in the row, whose weight scales the height
    private static RowConstraints createFieldRowConstraints(Field field, FieldEditorFX fieldEditor) {
        double rowHeight = field.isMultiLineDefined() ? MULTILINE_ROW_HEIGHT : ROW_HEIGHT;
        double height = rowHeight * fieldEditor.getWeight();
        RowConstraints rowConstraints = new RowConstraints(height, height, height);
        rowConstraints.setValignment(VPos.TOP);
        return rowConstraints;
    }

    /// Function to collect the fetched fields the user may correct
    ///
    /// Internal bookkeeping fields are left out, because they are not part of the fetched metadata.
    private List<Field> editableFields() {
        return entry.getFields().stream()
                    .filter(field -> !(field instanceof InternalField))
                    .sorted(Comparator.comparing(FieldsUtil::getDisplayName))
                    .toList();
    }

    /// The editors trigger undo on the keyboard shortcut. The regular action would undo the last change of the library,
    /// so this one undoes the last change made in this dialog instead.
    private UndoAction createUndoAction(DialogService dialogService, StateManager stateManager) {
        return new UndoAction(dialogService, stateManager) {
            @Override
            public void execute() {
                reviewUndoManager.undo();
            }
        };
    }

    /// Counterpart of [#createUndoAction(DialogService, StateManager)] for redo.
    private RedoAction createRedoAction(DialogService dialogService, StateManager stateManager) {
        return new RedoAction(dialogService, stateManager) {
            @Override
            public void execute() {
                reviewUndoManager.redo();
            }
        };
    }
}
