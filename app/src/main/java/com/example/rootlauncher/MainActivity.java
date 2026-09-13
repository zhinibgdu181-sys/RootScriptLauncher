package com.example.rootlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;
    private final ArrayList<String> scriptList = new ArrayList<>();
    private ScriptAdapter adapter;
    private volatile Process process;
    private volatile BufferedWriter writer;
    private String pendingScriptPath = null;
    private android.content.SharedPreferences prefs;
    private File busyboxFile;

    private final androidx.activity.result.ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK) return;
                        if (result.getData() == null) return;
                        Uri uri = result.getData().getData();
                        if (uri == null) return;
                        String displayName = getFileName(uri);
                        if (displayName == null || displayName.length() == 0) {
                            displayName = "script_" + System.currentTimeMillis() + ".sh";
                        }
                        File destFile = new File(getFilesDir(), displayName);
                        try {
                            InputStream is = getContentResolver().openInputStream(uri);
                            if (is == null) return;
                            FileOutputStream fos = new FileOutputStream(destFile);
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                            is.close(); fos.close();
                            Process chmod = new ProcessBuilder("chmod", "755", destFile.getAbsolutePath()).redirectErrorStream(true).start();
                            chmod.waitFor();
                            scriptList.add(destFile.getAbsolutePath());
                            adapter.notifyDataSetChanged();
                            saveScripts();
                        } catch (Exception ignored) {}
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnSend = findViewById(R.id.btnSend);

        prefs = getSharedPreferences("script_prefs", MODE_PRIVATE);
        Set<String> savedScripts = prefs.getStringSet("scripts", new HashSet<>());
        scriptList.addAll(savedScripts);

        adapter = new ScriptAdapter();
        lvScripts.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(intent);
        });

        btnSend.setOnClickListener(v -> {
            String input = etInput.getText().toString();
            if (input.length() == 0) return;
            if (writer == null) {
                executeCommand(input);
                return;
            }
            input += "\n";
            try {
                writer.write(input);
                writer.flush();
                etInput.setText("");
                appendColoredText(input, 0xFFFF00);
            } catch (Exception ignored) {}
        });

        new Thread(() -> { if (!checkRoot()) showRootDialog(); }).start();
    }

    private void executeCommand(String cmd) {
        etInput.setText("");
        new Thread(() -> {
            try {
                String finalCmd = "export PATH=/data/local/tmp:/system/bin:/system/xbin:/vendor/bin:$PATH; " + cmd;
                ProcessBuilder pb = new ProcessBuilder(findSu(), "-c", finalCmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                runOnUiThread(() -> appendColoredText("$ " + cmd + "\n", 0xFF00FFFF));
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                char[] buffer = new char[1024];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (count <= 0) continue;
                    final String rawOutput = new String(buffer, 0, count);
                    final String cleanOutput = cleanElfOutput(rawOutput);
                    if (cleanOutput.length() > 0) runOnUiThread(() -> appendText(cleanOutput));
                }
                p.waitFor();
            } catch (Exception ignored) {}
        }).start();
    }

    private String findSu() {
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String path : suPaths) { if (new File(path).exists()) return path; }
        return "su";
    }

    private boolean checkRoot() {
        try {
            Process p = new ProcessBuilder(findSu(), "-c", "id").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
            int exitCode = p.waitFor();
            return exitCode == 0 && output.toString().contains("uid=0");
        } catch (Exception e) { return false; }
    }

    private void showRootDialog() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("需要 root 权限")
                    .setMessage("本软件需要 root 权限才能执行 ELF。\n\n请在 KernelSU / Magisk 中允许本应用，然后点击「重试」。")
                    .setPositiveButton("重试", (dialog, which) -> {
                        new Thread(() -> {
                            if (checkRoot()) {
                                if (pendingScriptPath != null) {
                                    String path = pendingScriptPath;
                                    pendingScriptPath = null;
                                    runElfReal(path);
                                }
                            } else { showRootDialog(); }
                        }).start();
                    })
                    .setNegativeButton("退出", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        });
    }

    private String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void runElf(String scriptPath) {
        new Thread(() -> {
            if (!checkRoot()) { pendingScriptPath = scriptPath; showRootDialog(); return; }
            runElfReal(scriptPath);
        }).start();
    }

    private void runElfReal(String scriptPath) {
        try {
            File elf = new File(scriptPath);
            if (!elf.exists() || !elf.isFile()) { appendText("ELF 文件不存在\n"); return; }
            if (!extractAndPrepareBusybox()) { appendText("BusyBox 初始化失败\n"); return; }
            String suCmd = findSu();
            String elfDir = elf.getParent();
            if (elfDir == null) elfDir = getFilesDir().getAbsolutePath();
            String env = "export PATH=" + shellQuote(busyboxFile.getParent() + ":/system/bin:/system/xbin:/vendor/bin") + ":$PATH; " +
                    "export HOME=/data/local/tmp; export TMPDIR=/data/local/tmp; " +
                    "export LD_LIBRARY_PATH=/system/lib64:/vendor/lib64:$LD_LIBRARY_PATH; cd " + shellQuote(elfDir) + "; ";
            String elfCommand = "exec " + shellQuote(elf.getAbsolutePath());
            String command = env + shellQuote(busyboxFile.getAbsolutePath()) + " script -q -c " + shellQuote(elfCommand) + " /dev/null";
            ProcessBuilder pb = new ProcessBuilder(suCmd, "-c", command);
            pb.redirectErrorStream(false);
            try { pb.directory(new File(elfDir)); } catch (Exception ignored) {}
            process = pb.start();
            final Process currentProcess = process;
            writer = new BufferedWriter(new OutputStreamWriter(currentProcess.getOutputStream()));

            Thread stdoutThread = new Thread(() -> {
                try {
                    InputStreamReader reader = new InputStreamReader(currentProcess.getInputStream());
                    char[] buffer = new char[1024];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        if (count <= 0) continue;
                        String clean = cleanElfOutput(new String(buffer, 0, count));
                        if (clean.length() > 0) runOnUiThread(() -> appendText(clean));
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrThread = new Thread(() -> {
                try {
                    InputStreamReader reader = new InputStreamReader(currentProcess.getErrorStream());
                    char[] buffer = new char[1024];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        if (count <= 0) continue;
                        String clean = cleanElfOutput(new String(buffer, 0, count));
                        if (clean.length() > 0) runOnUiThread(() -> appendColoredText(clean, Color.RED));
                    }
                } catch (Exception ignored) {}
            });

            stdoutThread.start(); stderrThread.start();
            new Thread(() -> {
                try { currentProcess.waitFor(); stdoutThread.join(1000); stderrThread.join(1000); writer = null; }
                catch (Exception ignored) { writer = null; }
            }).start();
        } catch (Exception ignored) { writer = null; }
    }

    private boolean extractAndPrepareBusybox() {
        try {
            File tempFile = new File(getFilesDir(), "busybox_temp");
            try (InputStream is = getAssets().open("busybox"); FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192]; int len;
                while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            }
            if (!tempFile.exists() || tempFile.length() < 100000) return false;
            busyboxFile = new File("/data/local/tmp/busybox");
            String src = shellQuote(tempFile.getAbsolutePath());
            String dst = shellQuote(busyboxFile.getAbsolutePath());
            String installCommand = "rm -f " + dst + " ; cat " + src + " > " + dst + " ; chmod 755 " + dst;
            Process installProcess = new ProcessBuilder(findSu(), "-c", installCommand).redirectErrorStream(true).start();
            installProcess.waitFor();
            String scriptCheckCommand = dst + " --list";
            Process scriptCheckProcess = new ProcessBuilder(findSu(), "-c", scriptCheckCommand).redirectErrorStream(true).start();
            String appletList = readAll(scriptCheckProcess.getInputStream());
            scriptCheckProcess.waitFor();
            for (String applet : appletList.split("\\s+")) { if ("script".equals(applet.trim())) return true; }
            return false;
        } catch (Exception e) { return false; }
    }

    private String readAll(InputStream inputStream) {
        StringBuilder result = new StringBuilder();
        try {
            InputStreamReader reader = new InputStreamReader(inputStream);
            char[] buffer = new char[1024]; int count;
            while ((count = reader.read(buffer)) != -1) { if (count > 0) result.append(buffer, 0, count); }
        } catch (Exception ignored) {}
        return result.toString();
    }

    private String cleanElfOutput(String text) {
        if (text == null || text.length() == 0) return "";
        text = text.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        text = text.replaceAll("\\[(?:[0-9;?]+)m", "");
        text = text.replace("公益倒卖死全家", "");
        return text;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) result = cursor.getString(nameIndex);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) { int cut = result.lastIndexOf('/'); if (cut != -1) result = result.substring(cut + 1); }
        }
        return result;
    }

    private void saveScripts() { prefs.edit().putStringSet("scripts", new HashSet<>(scriptList)).apply(); }

    private class ScriptAdapter extends ArrayAdapter<String> {
        public ScriptAdapter() { super(MainActivity.this, 0, scriptList); }
        @NonNull @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_script, parent, false);
            String path = scriptList.get(position);
            String fileName = new File(path).getName();
            TextView tvName = convertView.findViewById(R.id.tvScriptName);
            Button btnRun = convertView.findViewById(R.id.btnRun);
            Button btnDelete = convertView.findViewById(R.id.btnDelete);
            tvName.setText(fileName);
            btnRun.setOnClickListener(v -> runElf(path));
            btnDelete.setOnClickListener(v -> { scriptList.remove(position); adapter.notifyDataSetChanged(); saveScripts(); });
            return convertView;
        }
    }

    private void appendColoredText(String text, int color) {
        if (text == null || text.length() == 0) return;
        runOnUiThread(() -> {
            SpannableString spannableString = new SpannableString(text);
            spannableString.setSpan(new ForegroundColorSpan(color), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvOutput.append(spannableString);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void appendText(String text) {
        if (text == null || text.length() == 0) return;
        runOnUiThread(() -> {
            tvOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        writer = null; process = null;
    }
        }
