package za.co.cporm.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import za.co.cporm.model.CPOrmConfiguration;
import za.co.cporm.model.CPOrmDatabase;
import za.co.cporm.model.generate.TableDetails;
import za.co.cporm.model.util.ManifestHelper;
import za.co.cporm.provider.util.UriMatcherHelper;
import za.co.cporm.util.CPOrmLog;

import java.util.Arrays;
import java.util.List;

/**
 * The base content provided that will expose all of the model objects.
 * Objects are expose in the form of authority/table_name/*
 */
public class CPOrmContentProvider extends ContentProvider {

    public static final String PARAMETER_OFFSET = "OFFSET";
    public static final String PARAMETER_LIMIT = "LIMIT";
    public static final String PARAMETER_SYNC = "IS_SYNC";

    private CPOrmConfiguration cPOrmConfiguration;
    private CPOrmDatabase database;
    private UriMatcherHelper uriMatcherHelper;
    private boolean debugEnabled;

    @Override
    public boolean onCreate() {

        cPOrmConfiguration = ManifestHelper.getConfiguration(getContext());
        database = new CPOrmDatabase(getContext(), cPOrmConfiguration);
        uriMatcherHelper = new UriMatcherHelper(getContext());
        uriMatcherHelper.init(getContext(), database.getcPOrmConfiguration(), database.getTableDetailsCache());

        debugEnabled = cPOrmConfiguration.isQueryLoggingEnabled();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getReadableDatabase();
        String limit = constructLimit(uri);

        if (debugEnabled) {
            CPOrmLog.d("********* Query **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Projection: " + Arrays.toString(projection));
            CPOrmLog.d("Selection: " + selection);
            CPOrmLog.d("Args: " + Arrays.toString(selectionArgs));
            CPOrmLog.d("Sort: " + sortOrder);
            CPOrmLog.d("Limit: " + limit);
        }

        Cursor cursor;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            cursor = db.query(tableDetails.getTableName(), projection, primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId}, null, null, null);
        } else
            cursor = db.query(tableDetails.getTableName(), projection, selection, selectionArgs, null, null, sortOrder, limit);

        cursor.setNotificationUri(getContext().getContentResolver(), uri);

        return cursor;
    }

    @Override
    public String getType(Uri uri) {

        return uriMatcherHelper.getType(uri);
    }

    @Override
    public Uri insert(Uri uri, ContentValues contentValues) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Insert **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Content Values: " + contentValues);
        }

        long insertId = db.insertOrThrow(tableDetails.getTableName(), null, contentValues);

        if (insertId == -1)
            throw new IllegalArgumentException("Failed to insert row for into table " + tableDetails.getTableName() + " using values " + contentValues);

        notifyChanges(uri, tableDetails);

        TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
        if (primaryKeyColumn.isAutoIncrement()) return uriMatcherHelper.generateSingleItemUri(tableDetails, insertId);
        else {

            String primaryKeyValue = contentValues.getAsString(primaryKeyColumn.getColumnName());
            return uriMatcherHelper.generateSingleItemUri(tableDetails, primaryKeyValue);
        }
    }

    @Override
    public int delete(Uri uri, String where, String[] args) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Delete **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Where: " + where);
            CPOrmLog.d("Args: " + Arrays.toString(args));
        }

        int deleteCount;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            deleteCount = db.delete(tableDetails.getTableName(), primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId});
        } else deleteCount = db.delete(tableDetails.getTableName(), where, args);

        if (deleteCount == 0)
            return deleteCount;

        notifyChanges(uri, tableDetails);


        return deleteCount;
    }

    @Override
    public int update(Uri uri, ContentValues contentValues, String where, String[] args) {

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Update **********");
            CPOrmLog.d("Uri: " + uri);
            CPOrmLog.d("Content Values: " + contentValues);
            CPOrmLog.d("Where: " + where);
            CPOrmLog.d("Args: " + Arrays.toString(args));
        }

        int updateCount;

        if (uriMatcherHelper.isSingleItemRequested(uri)) {

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            updateCount = db.update(tableDetails.getTableName(), contentValues, primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId});
        } else updateCount = db.update(tableDetails.getTableName(), contentValues, where, args);

        if (updateCount > 0 && shouldChangesBeNotified(tableDetails, contentValues)) {
            notifyChanges(uri, tableDetails);
        }

        return updateCount;
    }

    @Override
    public int bulkInsert(Uri uri, @NonNull ContentValues[] values) {

        int length = values.length;
        if (length == 0)
            return 0;

        TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);
        SQLiteDatabase db = database.getWritableDatabase();

        if (debugEnabled) {
            CPOrmLog.d("********* Bulk Insert **********");
            CPOrmLog.d("Uri: " + uri);
        }

        int count = 0;

        try {
            db.beginTransactionNonExclusive();
            String tableName = tableDetails.getTableName();
            for (int i = 0; i < length; i++) {

                db.insertOrThrow(tableName, null, values[i]);
                count++;

                if (count % 100 == 0)
                    db.yieldIfContendedSafely();
            }

            db.setTransactionSuccessful();

            notifyChanges(uri, tableDetails);
        } finally {
            db.endTransaction();
        }

        return count;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {

        if ("FindById".equals(method) && cPOrmConfiguration.allowContentProviderMethodCalling()) {

            if (extras == null)
                throw new IllegalArgumentException("Extras has to be provided");

            if (!extras.containsKey("URI"))
                throw new IllegalArgumentException("Extras Key URI has to be provided");

            Uri uri = extras.getParcelable("URI");

            if (!uriMatcherHelper.isSingleItemRequested(uri))
                throw new IllegalArgumentException("This is intended for single item access");

            TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);

            if (!tableDetails.isSerializable())
                throw new IllegalArgumentException("This model class is not serializable");

            SQLiteDatabase db = database.getReadableDatabase();

            if (debugEnabled) {
                CPOrmLog.d("********* Query **********");
                CPOrmLog.d("Uri: " + uri);
            }

            Cursor cursor;

            String itemId = uri.getLastPathSegment();
            TableDetails.ColumnDetails primaryKeyColumn = tableDetails.findPrimaryKeyColumn();
            cursor = db.query(tableDetails.getTableName(), null, primaryKeyColumn.getColumnName() + " = ?", new String[]{itemId}, null, null, null);

            try {
                if (cursor.moveToFirst()) {

                    Bundle result = new Bundle();
                    int columnCount = cursor.getColumnCount();
                    for (int i = 0; i < columnCount; i++) {

                        if (cursor.isNull(i))
                            continue;

                        String columnName = cursor.getColumnName(i);
                        TableDetails.ColumnDetails column = tableDetails.findColumn(columnName);
                        column.getColumnTypeMapping().setBundleValue(result, columnName, cursor, i);
                    }
                    return result;
                }
                return null;
            } finally {
                cursor.close();
            }
        } else if (("SelectFirst".equals(method) || "SelectLast".equals(method)) && cPOrmConfiguration.allowContentProviderMethodCalling()) {

            if (extras == null)
                throw new IllegalArgumentException("Extras has to be provided");

            if (!extras.containsKey("URI"))
                throw new IllegalArgumentException("Extras Key URI has to be provided");
            if (!extras.containsKey("Projection"))
                throw new IllegalArgumentException("Extras Key Selection has to be provided");
            if (!extras.containsKey("Selection"))
                throw new IllegalArgumentException("Extras Key Selection has to be provided");
            if (!extras.containsKey("SelectionArgs"))
                throw new IllegalArgumentException("Extras Key SelectionArgs has to be provided");
            if (!extras.containsKey("SortOrder"))
                throw new IllegalArgumentException("Extras Key SelectionArgs has to be provided");

            Uri uri = extras.getParcelable("URI");
            String[] projection = extras.getStringArray("Projection");
            String selection = extras.getString("Selection");
            String[] selectionArgs = extras.getStringArray("SelectionArgs");
            String sortOrder = extras.getString("SortOrder");

            TableDetails tableDetails = uriMatcherHelper.getTableDetails(uri);

            if (!tableDetails.isSerializable())
                throw new IllegalArgumentException("This model class is not serializable");

            SQLiteDatabase db = database.getReadableDatabase();
            String limit = constructLimit(uri);

            Cursor cursor = db.query(tableDetails.getTableName(), projection, selection, selectionArgs, null, null, sortOrder, limit);
            try {
                boolean cursorMoved;
                if ("SelectLast".equals(method)) cursorMoved = cursor.moveToLast();
                else cursorMoved = cursor.moveToFirst();

                if (cursorMoved) {

                    Bundle result = new Bundle();
                    int columnCount = cursor.getColumnCount();
                    for (int i = 0; i < columnCount; i++) {

                        if (cursor.isNull(i))
                            continue;

                        String columnName = cursor.getColumnName(i);
                        TableDetails.ColumnDetails column = tableDetails.findColumn(columnName);
                        column.getColumnTypeMapping().setBundleValue(result, columnName, cursor, i);
                    }
                    return result;
                }
                return null;
            } finally {
                cursor.close();
            }
        } else return super.call(method, arg, extras);
    }

    private String constructLimit(Uri uri) {

        String offsetParam = uri.getQueryParameter(PARAMETER_OFFSET);
        String limitParam = uri.getQueryParameter(PARAMETER_LIMIT);

        Integer offset = null;
        Integer limit = null;

        if (!TextUtils.isEmpty(offsetParam) && TextUtils.isDigitsOnly(offsetParam)) {
            offset = Integer.valueOf(offsetParam);
        }
        if (!TextUtils.isEmpty(limitParam) && TextUtils.isDigitsOnly(limitParam)) {
            limit = Integer.valueOf(limitParam);
        }

        if (limit == null && offset == null)
            return null;

        StringBuilder limitStatement = new StringBuilder();

        if (limit != null && offset != null) {
            limitStatement.append(offset);
            limitStatement.append(",");
            limitStatement.append(limit);
        } else if (limit != null) {
            limitStatement.append(limit);
        } else throw new IllegalArgumentException("A limit must also be provided when setting an offset");

        return limitStatement.toString();
    }

    private boolean shouldChangesBeNotified(TableDetails tableDetails, ContentValues contentValues) {

        boolean notify = false;

        for (String columnName : contentValues.keySet()) {

            TableDetails.ColumnDetails column = tableDetails.findColumn(columnName);
            if (column != null)
                notify = notify || column.notifyChanges();
        }

        return notify;
    }

    private void notifyChanges(Uri uri, TableDetails tableDetails) {

        Boolean sync = uri.getBooleanQueryParameter(PARAMETER_SYNC, true);
        getContext().getContentResolver().notifyChange(uri, null, sync);

        List<Class<?>> changeListeners = tableDetails.getChangeListeners();
        if (!changeListeners.isEmpty()) {

            int size = changeListeners.size();
            for (int i = 0; i < size; i++) {
                Class<?> changeListener = changeListeners.get(i);
                TableDetails changeListenerDetails = database.getTableDetailsCache().findTableDetails(getContext(), changeListener);

                if (changeListenerDetails == null)
                    continue;

                //Change listeners are registered on views, so the entire view needs to be updated if changes to its data occurs
                Uri changeUri = uriMatcherHelper.generateItemUri(changeListenerDetails);
                getContext().getContentResolver().notifyChange(changeUri, null, sync);
            }
        }
    }
}
