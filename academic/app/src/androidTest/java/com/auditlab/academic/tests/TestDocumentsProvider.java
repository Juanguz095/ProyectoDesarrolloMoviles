package com.auditlab.academic.tests;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone test provider. All files are synthetic private cache files of the test APK. */
public final class TestDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private File file(String id) {
        if (!id.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid fixture ID");
        return new File(getContext().getCacheDir(), "auditlab-document-" + id);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS });
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Root.COLUMN_ROOT_ID) || column.equals(Root.COLUMN_DOCUMENT_ID)) value = "root";
            else if (column.equals(Root.COLUMN_TITLE)) value = "AuditLab test documents";
            else if (column.equals(Root.COLUMN_FLAGS)) value = Root.FLAG_SUPPORTS_CREATE;
            else if (column.equals(Root.COLUMN_MIME_TYPES)) value = "text/csv";
            row.add(column, value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        File target = file(id);
        if (!target.exists()) throw new FileNotFoundException("Synthetic document absent");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE });
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = id + ".csv";
            else if (column.equals(Document.COLUMN_MIME_TYPE)) value = "text/csv";
            else if (column.equals(Document.COLUMN_FLAGS)) value = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
            else if (column.equals(Document.COLUMN_SIZE)) value = target.length();
            row.add(column, value);
        }
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        return new MatrixCursor(projection != null ? projection : new String[] { Document.COLUMN_DOCUMENT_ID });
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.startsWith("fail-")) throw new FileNotFoundException("Synthetic provider failure");
        File target = file(id);
        if (mode.contains("w")) {
            if (id.startsWith("partial-")) {
                try (FileOutputStream stream = new FileOutputStream(target)) {
                    stream.write("partial fixture".getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) { throw new FileNotFoundException("Fixture preparation failed"); }
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
        }
        if (!target.exists()) throw new FileNotFoundException("Synthetic document absent");
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!file(id).delete()) throw new FileNotFoundException("Synthetic document absent");
    }
}
