package com.example.rootlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
    private volatile boolean elfRunning = false;

    private String pendingScriptPath = null;
    private android.content.SharedPreferences prefs;
    private File busyboxFile;
    private boolean keyboardVisible = false;

    private static final int SCRIPT_LIST_KEYBOARD_DP = 120;

    private static final String BUILTIN_KAIROS = "Kairos_Driver_Loader_Release_90f76e9.sh";
    private static final String BUILTIN_TIME = "TIME_Cloud_Loader_Release_1732727.sh";
    private static final String BUSYBOX_ASSET = "busybox";

    private static final String RUNTIME_DIR = "/data/local/tmp/com.example.rootlauncher/files";
    private static final String RUNTIME_BUSYBOX = RUNTIME_DIR + "/busybox";

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK) return;
                        if (result.getData() == null) return;
                        Uri uri = result.getData().getData();
                        if (uri == null) return;

                        String displayName = getFileName(uri);
                        if (displayName == null || displayName.length() == 0) {
                            displayName = "script_" + System.currentTimeMillis() + ".sh";
                        }

                        final String finalDisplayName = sanitizeFileName(displayName);

                        new Thread(() -> {
                            try {
                                File tempFile = new File(getFilesDir(), "import_" + System.currentTimeMillis() + "_" + finalDisplayName);
                                InputStream is = getContentResolver().openInputStream(uri);
                                if (is == null) {
                                    appendText("[添加失败] 无法读取文件\n");
                                    return;
                                }

                                FileOutputStream fos = new FileOutputStream(tempFile);
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                                is.close();
                                fos.close();

                                if (!tempFile.exists() || tempFile.length() == 0) {
                                    appendText("[添加失败] 文件为空\n");
                                    tempFile.delete();
                                    return;
                                }

                                if (!prepareRuntimeDir()) {
                                    appendText("[添加失败] 无法创建运行目录\n");
                                    tempFile.delete();
                                    return;
                                }

                                String runtimePath = RUNTIME_DIR + "/" + finalDisplayName;
                                if (!copyFileAsRoot(tempFile.getAbsolutePath(), runtimePath)) {
                                    appendText("[添加失败] 无法复制到运行目录\n");
                                    tempFile.delete();
                                    return;
                                }

                                chmod755(runtimePath);
                                tempFile.delete();

                                synchronized (scriptList) {
                                    if (!scriptList.contains(runtimePath)) {
                                        scriptList.add(runtimePath);
                                    }
                                }
                                saveScripts();

                                runOnUiThread(() -> {
                                    if (adapter != null) {
                                        adapter.notifyDataSetChanged();
                                    }
                                    appendText("[+] 已添加：" + finalDisplayName + "\n");
                                });

                            } catch (Exception e) {
                                appendText("[添加文件失败] " + e.getMessage() + "\n");
                            }
                        }).start();
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_main);

        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);

        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnSend = findViewById(R.id.btnSend);

        prefs = getSharedPreferences("script_prefs", MODE_PRIVATE);

        Set<String> savedScripts = prefs.getStringSet("scripts", new HashSet<>());
        for (String savedPath : savedScripts) {
            String normalized = normalizeSavedPath(savedPath);
            if (normalized != null && !scriptList.contains(normalized)) {
                scriptList.add(normalized);
            }
        }

        addBuiltinScript(BUILTIN_KAIROS);
        addBuiltinScript(BUILTIN_TIME);

        adapter = new ScriptAdapter();
        lvScripts.setAdapter(adapter);
        setupKeyboardListener();

        new Thread(() -> {
            if (!checkRoot()) {
                showRootDialog();
                return;
            }
            prepareRuntimeDir();
            installBuiltinAsset(BUILTIN_KAIROS);
            installBuiltinAsset(BUILTIN_TIME);
        }).start();

        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(intent);
        });

        btnSend.setOnClickListener(v -> {
            String input = etInput.getText().toString();
            if (input.trim().isEmpty()) return;

            if (elfRunning && process != null && writer != null) {
                sendInputToElf(input);
            } else {
                executeCommand(input);
            }
        });
    }

    private void setupKeyboardListener() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (lvScripts == null) return;

            Rect visibleRect = new Rect();
            rootView.getWindowVisibleDisplayFrame(visibleRect);
            int rootHeight = rootView.getRootView().getHeight();
            int visibleHeight = visibleRect.bottom - visibleRect.top;
            int keyboardHeight = rootHeight - visibleHeight;

            boolean nowVisible = keyboardHeight > rootHeight * 0.15f;
            if (nowVisible == keyboardVisible) return;

            keyboardVisible = nowVisible;
            setScriptListKeyboardMode(keyboardVisible);
        });
    }

    private void setScriptListKeyboardMode(boolean keyboardMode) {
        if (lvScripts == null) return;

        ViewGroup.LayoutParams rawParams = lvScripts.getLayoutParams();
        if (!(rawParams instanceof ConstraintLayout.LayoutParams)) return;

        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) rawParams;

        if (keyboardMode) {
            params.height = dpToPx(SCRIPT_LIST_KEYBOARD_DP);
            params.matchConstraintPercentHeight = -1f;
        } else {
            params.height = 0;
            params.matchConstraintPercentHeight = 0.55f;
        }

        lvScripts.setLayoutParams(params);
        lvScripts.requestLayout();

        if (keyboardMode && scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addBuiltinScript(String assetName) {
        String runtimePath = RUNTIME_DIR + "/" + assetName;
        if (!scriptList.contains(runtimePath)) {
            scriptList.add(runtimePath);
        }
        saveScripts();
    }

    private boolean installBuiltinAsset(String assetName) {
        try {
            if (!prepareRuntimeDir()) return false;

            File tempFile = new File(getFilesDir(), "builtin_" + assetName);
            InputStream is = getAssets().open(assetName);
            FileOutputStream fos = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            is.close();
            fos.close();

            if (!tempFile.exists() || tempFile.length() == 0) {
                tempFile.delete();
                appendText("[内置 ELF] 文件异常：" + assetName + "\n");
                return false;
            }

            String runtimePath = RUNTIME_DIR + "/" + assetName;
            boolean copied = copyFileAsRoot(tempFile.getAbsolutePath(), runtimePath);
            tempFile.delete();

            if (!copied) {
                appendText("[内置 ELF] 安装失败：" + assetName + "\n");
                return false;
            }

            chmod755(runtimePath);
            return true;
        } catch (Exception e) {
            appendText("[内置 ELF] 安装异常：" + assetName + " : " + e.getMessage() + "\n");
            return false;
        }
    }

    private void sendInputToElf(String input) {
        try {
            BufferedWriter currentWriter = writer;
            Process currentProcess = process;

            if (currentWriter == null || currentProcess == null || !elfRunning) {
                appendText("[输入通道尚未建立]\n");
                return;
            }

            currentWriter.write(input);
            currentWriter.newLine();
            currentWriter.flush();

            etInput.post(() -> etInput.setText(""));
        } catch (Exception e) {
            appendText("[ELF 输入失败] " + e.getMessage() + "\n");
        }
    }

    private void executeCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;

        final String command = cmd.trim();
        appendText("$ " + command + "\n");
        etInput.setText("");

        new Thread(() -> {
            try {
                String finalCmd = "export PATH=" + shellQuote(RUNTIME_DIR + ":/data/local/tmp:/system/bin:/system/xbin:/vendor/bin") + ":$PATH; " + command;

                ProcessBuilder pb = new ProcessBuilder(findSu(), "-c", finalCmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                char[] buffer = new char[1024];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (count <= 0) continue;
                    String raw = new String(buffer, 0, count);
                    String clean = cleanElfOutput(raw);
                    if (!clean.isEmpty()) {
                        runOnUiThread(() -> appendText(clean));
                    }
                }

                int exitCode = p.waitFor();
                runOnUiThread(() -> appendText("\n[exit " + exitCode + "]\n"));
            } catch (Exception e) {
                runOnUiThread(() -> appendText("\n[执行失败] " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private String findSu() {
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String path : suPaths) {
            if (new File(path).exists()) return path;
        }
        return "su";
    }

    private boolean checkRoot() {
        try {
            Process p = new ProcessBuilder(findSu(), "-c", "id").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
            int exitCode = p.waitFor();
            return exitCode == 0 && output.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private void showRootDialog() {
        runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("需要 Root 权限")
                .setMessage("本软件需要 Root 权限才能执行命令和 ELF。\n\n请在 KernelSU / Magisk 中允许本应用，然后点击「重试」。")
                .setPositiveButton("重试", (dialog, which) -> new Thread(() -> {
                    if (checkRoot()) {
                        prepareRuntimeDir();
                        if (pendingScriptPath != null) {
                            String path = pendingScriptPath;
                            pendingScriptPath = null;
                            runElfReal(path);
                        }
                    } else {
                        showRootDialog();
                    }
                }).start())
                .setNegativeButton("退出", (dialog, which) -> finish())
                .setCancelable(false)
                .show());
    }

    private String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void runElf(String scriptPath) {
        new Thread(() -> {
            if (!checkRoot()) {
                pendingScriptPath = scriptPath;
                showRootDialog();
                return;
            }
            runElfReal(scriptPath);
        }).start();
    }

    private void runElfReal(String scriptPath) {
        stopCurrentElf();
        try {
            if (!prepareRuntimeDir()) {
                appendText("[ELF] 无法创建运行目录\n");
                return;
            }

            String runtimePath = normalizeSavedPath(scriptPath);
            if (runtimePath == null) {
                appendText("[ELF] 无效路径\n");
                return;
            }

            String fileName = new File(runtimePath).getName();
            if (BUILTIN_KAIROS.equals(fileName) || BUILTIN_TIME.equals(fileName)) {
                File builtinFile = new File(runtimePath);
                if (!builtinFile.exists() || builtinFile.length() == 0) {
                    if (!installBuiltinAsset(fileName)) {
                        appendText("[ELF] 内置文件安装失败：" + fileName + "\n");
                        return;
                    }
                }
            }

            File elf = new File(runtimePath);
            if (!elf.exists() || !elf.isFile() || elf.length() == 0) {
                appendText("[ELF] 文件不存在：" + runtimePath + "\n");
                return;
            }

            chmod755(runtimePath);

            // 检查 BusyBox 准备情况
            if (!extractAndPrepareBusybox()) {
                appendText("[ELF] APK 内置 BusyBox 初始化失败\n");
                return;
            }

            String suCmd = findSu();
            String elfDir = elf.getParent();
            if (elfDir == null) elfDir = RUNTIME_DIR;

            String env = "export PATH=" + shellQuote(RUNTIME_DIR + ":/data/local/tmp:/system/bin:/system/xbin:/vendor/bin") + ":$PATH; " +
                    "export HOME=" + shellQuote(RUNTIME_DIR) + "; " +
                    "export TMPDIR=" + shellQuote(RUNTIME_DIR) + "; " +
                    "export LD_LIBRARY_PATH=" + shellQuote("/system/lib64:/vendor/lib64") + ":$LD_LIBRARY_PATH; " +
                    "cd " + shellQuote(elfDir) + "; ";

            String elfCommand = "exec " + shellQuote(elf.getAbsolutePath());

            String command = env + shellQuote(busyboxFile.getAbsolutePath()) + " script -q -c " + shellQuote(elfCommand) + " /dev/null";

            ProcessBuilder pb = new ProcessBuilder(suCmd, "-c", command);
            pb.redirectErrorStream(false);
            try {
                pb.directory(new File(elfDir));
            } catch (Exception ignored) {}

            process = pb.start();
            final Process currentProcess = process;

            writer = new BufferedWriter(new OutputStreamWriter(currentProcess.getOutputStream(), StandardCharsets.UTF_8));
            elfRunning = true;

            appendText("[+] ELF 已启动\n");
            appendText("[+] BusyBox：" + RUNTIME_BUSYBOX + "\n");
            appendText("[+] ELF：" + runtimePath + "\n");

            Thread stdoutThread = new Thread(() -> {
                try {
                    InputStreamReader reader = new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8);
                    char[] buffer = new char[1024];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        if (count <= 0) continue;
                        String raw = new String(buffer, 0, count);
                        String clean = cleanElfOutput(raw);
                        if (!clean.isEmpty()) {
                            runOnUiThread(() -> appendText(clean));
                        }
                    }
                } catch (Exception ignored) {}
            });
            stdoutThread.setName("ELF-stdout");

            Thread stderrThread = new Thread(() -> {
                try {
                    InputStreamReader reader = new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8);
                    char[] buffer = new char[1024];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        if (count <= 0) continue;
                        String raw = new String(buffer, 0, count);
                        String clean = cleanElfOutput(raw);
                        if (!clean.isEmpty()) {
                            runOnUiThread(() -> appendText(clean));
                        }
                    }
                } catch (Exception ignored) {}
            });
            stderrThread.setName("ELF-stderr");

            stdoutThread.start();
            stderrThread.start();

            new Thread(() -> {
                try {
                    int exitCode = currentProcess.waitFor();
                    stdoutThread.join(1000);
                    stderrThread.join(1000);
                    final int code = exitCode;
                    runOnUiThread(() -> appendText("\n[ELF exit " + code + "]\n"));
                } catch (Exception ignored) {
                } finally {
                    if (process == currentProcess) {
                        writer = null;
                        process = null;
                        elfRunning = false;
                    }
                }
            }).start();

        } catch (Exception e) {
            writer = null;
            process = null;
            elfRunning = false;
            appendText("[ELF 启动失败] " + e.getMessage() + "\n");
        }
    }

    private void stopCurrentElf() {
        try {
            BufferedWriter currentWriter = writer;
            if (currentWriter != null) currentWriter.close();
        } catch (Exception ignored) {}
        writer = null;

        try {
            Process currentProcess = process;
            if (currentProcess != null) {
                currentProcess.destroy();
                if (currentProcess.isAlive()) {
                    currentProcess.destroyForcibly();
                }
            }
        } catch (Exception ignored) {}
        process = null;
        elfRunning = false;
    }

    private boolean prepareRuntimeDir() {
        try {
            String command = "mkdir -p " + shellQuote(RUNTIME_DIR) + " && chmod 755 " + shellQuote(RUNTIME_DIR);
            Process p = new ProcessBuilder(findSu(), "-c", command).redirectErrorStream(true).start();
            String output = readAll(p.getInputStream());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                appendText("[运行目录创建失败] " + output + "\n");
                return false;
            }
            return true;
        } catch (Exception e) {
            appendText("[运行目录异常] " + e.getMessage() + "\n");
            return false;
        }
    }

    private boolean copyFileAsRoot(String source, String destination) {
        try {
            String command = "mkdir -p " + shellQuote(RUNTIME_DIR) + "; " +
                    "cat " + shellQuote(source) + " > " + shellQuote(destination) + "; " +
                    "chmod 755 " + shellQuote(destination);
            Process p = new ProcessBuilder(findSu(), "-c", command).redirectErrorStream(true).start();
            String output = readAll(p.getInputStream());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                appendText("[Root复制失败] " + output + "\n");
                return false;
            }
            return true;
        } catch (Exception e) {
            appendText("[Root复制异常] " + e.getMessage() + "\n");
            return false;
        }
    }

    private boolean chmod755(String path) {
        try {
            Process p = new ProcessBuilder(findSu(), "-c", "chmod 755 " + shellQuote(path)).redirectErrorStream(true).start();
            readAll(p.getInputStream());
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean extractAndPrepareBusybox() {
        File tempFile = new File(getFilesDir(), "busybox_temp");
        try {
            InputStream is = getAssets().open(BUSYBOX_ASSET);
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            is.close();
            fos.close();

            if (!tempFile.exists() || tempFile.length() < 100000) {
                appendText("[BusyBox] assets/busybox 文件异常\n");
                return false;
            }

            if (!prepareRuntimeDir()) return false;

            String destination = RUNTIME_BUSYBOX;
            String src = shellQuote(tempFile.getAbsolutePath());
            String dst = shellQuote(destination);

            String installCommand = "cat " + src + " > " + dst + "; chmod 755 " + dst;
            Process installProcess = new ProcessBuilder(findSu(), "-c", installCommand).redirectErrorStream(true).start();
            String installOutput = readAll(installProcess.getInputStream());
            int installExit = installProcess.waitFor();

            if (installExit != 0) {
                appendText("[BusyBox] 安装失败\n" + installOutput);
                return false;
            }

            busyboxFile = new File(RUNTIME_BUSYBOX);

            Process fileCheck = new ProcessBuilder(findSu(), "-c", "ls -l " + dst).redirectErrorStream(true).start();
            String fileInfo = readAll(fileCheck.getInputStream());
            int fileExit = fileCheck.waitFor();

            if (fileExit != 0 || !busyboxFile.exists()) {
                appendText("[BusyBox] 安装文件不存在\n" + fileInfo);
                return false;
            }

            Process versionProcess = new ProcessBuilder(findSu(), "-c", dst + " --help").redirectErrorStream(true).start();
            String versionOutput = readAll(versionProcess.getInputStream());
            int versionExit = versionProcess.waitFor();

            if (versionExit != 0) {
                appendText("[BusyBox] 无法执行\n" + versionOutput);
                return false;
            }

            Process listProcess = new ProcessBuilder(findSu(), "-c", dst + " --list").redirectErrorStream(true).start();
            String appletList = readAll(listProcess.getInputStream());
            int listExit = listProcess.waitFor();

            if (listExit != 0) {
                appendText("[BusyBox] 无法读取 applet\n" + appletList);
                return false;
            }

            boolean hasScript = hasBusyboxApplet(appletList, "script");
            if (!hasScript) {
                appendText("[BusyBox] 不包含 script applet\n");
                return false;
            }

            appendText("[+] APK 内置 BusyBox 已准备\n");
            return true;

        } catch (Exception e) {
            appendText("[BusyBox] 初始化异常：" + e.getMessage() + "\n");
            return false;
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete();
            } catch (Exception ignored) {}
        }
    }

    private boolean hasBusyboxApplet(String appletList, String wanted) {
        if (appletList == null || wanted == null) return false;
        String[] applets = appletList.split("\\s+");
        for (String applet : applets) {
            if (wanted.equals(applet.trim())) return true;
        }
        return false;
    }

    private String readAll(InputStream inputStream) {
        StringBuilder result = new StringBuilder();
        if (inputStream == null) return "";
        try {
            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (count > 0) result.append(buffer, 0, count);
            }
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
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "script_" + System.currentTimeMillis() + ".sh";
        }
        name = name.replace("/", "_");
        name = name.replace("\\", "_");
        name = name.replace("\u0000", "_");
        if (".".equals(name) || "..".equals(name)) {
            name = "script_" + System.currentTimeMillis() + ".sh";
        }
        return name;
    }

    private String normalizeSavedPath(String savedPath) {
        if (savedPath == null || savedPath.trim().isEmpty()) return null;
        savedPath = savedPath.trim();
        if (savedPath.startsWith(RUNTIME_DIR + "/")) return savedPath;

        String fileName = new File(savedPath).getName();
        if (fileName == null || fileName.isEmpty()) return null;

        String newPath = RUNTIME_DIR + "/" + fileName;
        if (new File(newPath).exists()) return newPath;
        if (BUILTIN_KAIROS.equals(fileName) || BUILTIN_TIME.equals(fileName)) return newPath;
        return newPath;
    }

    private void saveScripts() {
        if (prefs == null) return;
        synchronized (scriptList) {
            prefs.edit().putStringSet("scripts", new HashSet<>(scriptList)).apply();
        }
    }

    private class ScriptAdapter extends ArrayAdapter<String> {
        ScriptAdapter() {
            super(MainActivity.this, 0, scriptList);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_script, parent, false);
            }

            String path;
            synchronized (scriptList) {
                if (position < 0 || position >= scriptList.size()) {
                    return convertView;
                }
                path = scriptList.get(position);
            }

            String fileName = new File(path).getName();
            TextView tvName = convertView.findViewById(R.id.tvScriptName);
            Button btnRun = convertView.findViewById(R.id.btnRun);
            Button btnDelete = convertView.findViewById(R.id.btnDelete);

            if (tvName != null) tvName.setText(fileName);
            if (btnRun != null) btnRun.setOnClickListener(v -> runElf(path));
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    synchronized (scriptList) {
                        if (position >= 0 && position < scriptList.size()) {
                            scriptList.remove(position);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    saveScripts();
                });
            }
            return convertView;
        }
    }

    private void appendText(String text) {
        if (text == null || text.length() == 0) return;
        runOnUiThread(() -> {
            if (tvOutput == null) return;
            tvOutput.append(text);
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopCurrentElf();
        super.onDestroy();
    }
                            }
