package org.jabref.gui.entryeditor.citationrelationtab;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.jabref.gui.util.BaseDialog;
import org.jabref.gui.util.FieldsUtil;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.strings.StringUtil;
import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryType;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;
import org.jabref.model.entry.types.EntryType;

import org.jspecify.annotations.NullMarked;

/// Shows one entry with its type and fields editable, so that the
/// metadata can be corrected before the entry becomes part of the library.
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
    private static final int MULTILINE_ROW_COUNT = 4;

    private final BibEntry entry;
    private final ComboBox<EntryType> entryTypeCombo = new ComboBox<>();
    private final SequencedMap<Field, TextInputControl> fieldEditors = new LinkedHashMap<>();

    /// Function to build the review dialog for one fetched entry
    ///
    /// @param entry             fetched entry to review, edited in place once it is added
    /// @param position          one-based number of this entry within the review
    /// @param total             number of entries to review
    /// @param databaseMode      mode deciding which entry types are offered
    /// @param entryTypesManager source of the selectable entry types
    public CitationEntryReviewDialog(BibEntry entry,
                                     int position,
                                     int total,
                                     BibDatabaseMode databaseMode,
                                     BibEntryTypesManager entryTypesManager) {
        this.entry = entry;

        setTitle(Localization.lang("Review entry %0 of %1", position, total));

        ButtonType addButtonType = new ButtonType(Localization.lang("Add to library"), ButtonBar.ButtonData.OK_DONE);
        ButtonType skipButtonType = new ButtonType(Localization.lang("Skip entry"), ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType stopButtonType = new ButtonType(Localization.lang("Stop review"), ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().setAll(addButtonType, skipButtonType, stopButtonType);

        setUpEntryTypeCombo(databaseMode, entryTypesManager);
        getDialogPane().setContent(createContent());

        setResultConverter(buttonType -> {
            if (addButtonType.equals(buttonType)) {
                applyEdits();
                return Decision.ADD;
            }
            if (skipButtonType.equals(buttonType)) {
                return Decision.SKIP;
            }
            return Decision.STOP;
        });
    }

    /// Function to fill the entry type combo box with the types of the current library mode
    ///
    /// @param databaseMode      mode deciding which entry types are offered
    /// @param entryTypesManager source of the selectable entry types
    private void setUpEntryTypeCombo(BibDatabaseMode databaseMode, BibEntryTypesManager entryTypesManager) {
        entryTypeCombo.getItems().setAll(entryTypesManager.getAllTypes(databaseMode).stream()
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
    private VBox createContent() {
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

        int row = 1;
        for (Map.Entry<Field, String> fetchedField : editableFields()) {
            Field field = fetchedField.getKey();
            TextInputControl editor = createEditor(field, fetchedField.getValue());
            fieldEditors.put(field, editor);
            fieldGrid.add(new Label(FieldsUtil.getDisplayName(field)), 0, row);
            fieldGrid.add(editor, 1, row);
            row++;
        }

        ScrollPane scrollPane = new ScrollPane(fieldGrid);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox content = new VBox(10, summary, scrollPane);
        content.setPadding(new Insets(10));
        return content;
    }

    /// Function to collect the fetched fields the user may correct
    ///
    /// Internal bookkeeping fields are left out, because they are not part of the fetched metadata.
    private List<Map.Entry<Field, String>> editableFields() {
        return entry.getFieldMap().entrySet().stream()
                    .filter(fetchedField -> !(fetchedField.getKey() instanceof InternalField))
                    .sorted(Comparator.comparing(fetchedField -> FieldsUtil.getDisplayName(fetchedField.getKey())))
                    .toList();
    }

    /// Function to create the editor of one field, a text area for the multiline fields
    ///
    /// @param field field the editor belongs to
    /// @param value fetched value to show
    private static TextInputControl createEditor(Field field, String value) {
        if (field.isMultiLineDefined()) {
            TextArea textArea = new TextArea(value);
            textArea.setPrefRowCount(MULTILINE_ROW_COUNT);
            textArea.setWrapText(true);
            return textArea;
        }
        return new TextField(value);
    }

    /// Function to write the edited entry type and field values back into the entry
    ///
    /// A field left blank is cleared, so that emptying an editor removes the field from the entry.
    private void applyEdits() {
        entry.setType(entryTypeCombo.getValue());
        fieldEditors.forEach((field, editor) -> {
            String value = editor.getText();
            if (StringUtil.isBlank(value)) {
                entry.clearField(field);
            } else {
                entry.setField(field, value.trim());
            }
        });
    }
}
