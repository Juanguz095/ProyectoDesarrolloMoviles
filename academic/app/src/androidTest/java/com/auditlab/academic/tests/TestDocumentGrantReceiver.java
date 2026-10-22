package com.auditlab.academic.tests;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Java because this component runs in the standalone test APK, without target Kotlin classes. */
public final class TestDocumentGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !"com.auditlab.academic.test.documents".equals(uri.getAuthority()) ||
                uri.getPath() == null || !uri.getPath().startsWith("/document/")) throw new IllegalArgumentException("Not a test document");
        context.grantUriPermission("com.auditlab.academic", uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        setResultCode(Activity.RESULT_OK);
    }
}
