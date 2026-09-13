package com.example.rootlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

    // ============================================================
    // 文件选择器
    // ============================================================

    private final androidx.activity.result.ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode() != Activity.RESULT_OK) {
                            return;
                        }

                        if (result.getData() == null) {
                            return;
                        }

                        Uri uri = result.getData().getData();

                        if (uri == null) {
                            return;
                        }

                        String displayName = getFileName(uri);

                        if (displayName == null || displayName.length() == 0) {
                            displayName =
                                    "script_" + System.currentTimeMillis() + ".sh";
                        }

                        File destFile =
                                new File(getFilesDir(), displayName);

                        try {

                            InputStream is =
                                    getContentResolver().openInputStream(uri);

                            if (is == null) {
                                return;
                            }

                            FileOutputStream fos =
                                    new FileOutputStream(destFile);

                            byte[] buffer = new byte[8192];

                            int len;

                            while ((len = is.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }

                            is.close();
                            fos.close();

                            /*
                             * 给 ELF / 脚本执行权限。
                             */
                            Process chmod =
                                    new ProcessBuilder(
                                            "chmod",
                                            "755",
                                            destFile.getAbsolutePath()
                                    )
                                            .redirectErrorStream(true)
                                            .start();

                            chmod.waitFor();

                            scriptList.add(
                                    destFile.getAbsolutePath()
                            );

                            adapter.notifyDataSetChanged();

                            saveScripts();

                        } catch (Exception ignored) {
                        }
                    }
            );

    // ============================================================
    // onCreate
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);

        Button btnAdd =
                findViewById(R.id.btnAdd);

        Button btnSend =
                findViewById(R.id.btnSend);

        prefs =
                getSharedPreferences(
                        "script_prefs",
                        MODE_PRIVATE
                );

        Set<String> savedScripts =
                prefs.getStringSet(
                        "scripts",
                        new HashSet<>()
                );

        scriptList.addAll(savedScripts);

        adapter = new ScriptAdapter();

        lvScripts.setAdapter(adapter);

        // ========================================================
        // 添加 ELF / 脚本
        // ========================================================

        btnAdd.setOnClickListener(v -> {

            Intent intent =
                    new Intent(Intent.ACTION_GET_CONTENT);

            intent.setType("*/*");

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            filePickerLauncher.launch(intent);
        });

        // ========================================================
        // 发送输入
        // ========================================================

        btnSend.setOnClickListener(v -> {

            String input =
                    etInput.getText().toString();

            if (input.length() == 0) {
                return;
            }

            /*
             * 如果当前没有 ELF 在运行，
             * 允许直接执行 root 命令。
             */
            if (writer == null) {

                executeCommand(input);

                return;
            }

            input += "\n";

            try {

                writer.write(input);
                writer.flush();

                etInput.setText("");

                /*
                 * 不再显示：
                 *
                 * >>> 1
                 *
                 * 保持界面只显示 ELF 输出。
                 */

            } catch (Exception ignored) {
            }
        });

        // ========================================================
        // 检查 Root
        // ========================================================

        new Thread(() -> {

            if (!checkRoot()) {
                showRootDialog();
            }

        }).start();
    }

    // ============================================================
    // 直接执行 root 命令
    //
    // 仅用于调试环境。
    // ============================================================

    private void executeCommand(String cmd) {

        etInput.setText("");

        new Thread(() -> {

            try {

                String finalCmd =
                        "export PATH=/data/local/tmp:/system/bin:/system/xbin:/vendor/bin:$PATH; "
                                + cmd;

                ProcessBuilder pb =
                        new ProcessBuilder(
                                findSu(),
                                "-c",
                                finalCmd
                        );

                pb.redirectErrorStream(true);

                Process p = pb.start();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        p.getInputStream()
                                )
                        );

                char[] buffer = new char[1024];

                int count;

                while ((count = reader.read(buffer)) != -1) {

                    if (count <= 0) {
                        continue;
                    }

                    final String rawOutput =
                            new String(
                                    buffer,
                                    0,
                                    count
                            );

                    final String cleanOutput =
                            cleanElfOutput(rawOutput);

                    if (cleanOutput.length() > 0) {

                        runOnUiThread(() ->
                                appendText(cleanOutput)
                        );
                    }
                }

                p.waitFor();

            } catch (Exception ignored) {
            }

        }).start();
    }

    // ============================================================
    // 查找 su
    // ============================================================

    private String findSu() {

        String[] suPaths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su"
        };

        for (String path : suPaths) {

            if (new File(path).exists()) {
                return path;
            }
        }

        return "su";
    }

    // ============================================================
    // Root 检查
    // ============================================================

    private boolean checkRoot() {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "id"
                    )
                            .redirectErrorStream(true)
                            .start();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    p.getInputStream()
                            )
                    );

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                output.append(line);
            }

            int exitCode =
                    p.waitFor();

            return exitCode == 0
                    && output.toString().contains("uid=0");

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // Root 弹窗
    // ============================================================

    private void showRootDialog() {

        runOnUiThread(() -> {

            new AlertDialog.Builder(
                    MainActivity.this
            )
                    .setTitle("需要 root 权限")

                    .setMessage(
                            "本软件需要 root 权限才能执行 ELF。\n\n"
                                    + "请在 KernelSU / Magisk 中允许本应用，"
                                    + "然后点击「重试」。"
                    )

                    .setPositiveButton(
                            "重试",
                            (dialog, which) -> {

                                new Thread(() -> {

                                    if (checkRoot()) {

                                        if (pendingScriptPath != null) {

                                            String path =
                                                    pendingScriptPath;

                                            pendingScriptPath =
                                                    null;

                                            runElfReal(path);
                                        }

                                    } else {

                                        showRootDialog();
                                    }

                                }).start();
                            }
                    )

                    .setNegativeButton(
                            "退出",
                            (dialog, which) ->
                                    finish()
                    )

                    .setCancelable(false)

                    .show();
        });
    }

    // ============================================================
    // Shell 参数安全引用
    // ============================================================

    private String shellQuote(String value) {

        if (value == null) {
            return "''";
        }

        return "'"
                + value.replace(
                        "'",
                        "'\\''"
                )
                + "'";
    }

    // ============================================================
    // 启动 ELF
    // ============================================================

    private void runElf(String scriptPath) {

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                showRootDialog();

                return;
            }

            runElfReal(scriptPath);

        }).start();
    }

    // ============================================================
    // 真正启动 ELF
    // ============================================================

    private void runElfReal(String scriptPath) {

        try {

            File elf =
                    new File(scriptPath);

            if (!elf.exists() || !elf.isFile()) {

                appendText(
                        "ELF 文件不存在\n"
                );

                return;
            }

            // ====================================================
            // 准备 BusyBox
            // ====================================================

            if (!extractAndPrepareBusybox()) {

                appendText(
                        "BusyBox 初始化失败\n"
                );

                return;
            }

            String suCmd =
                    findSu();

            // ====================================================
            // ELF 所在目录
            // ====================================================

            String elfDir =
                    elf.getParent();

            if (elfDir == null) {

                elfDir =
                        getFilesDir()
                                .getAbsolutePath();
            }

            // ====================================================
            // 设置运行环境
            // ====================================================

            String env =

                    /*
                     * BusyBox + Android 系统 PATH
                     */
                    "export PATH="
                            + shellQuote(
                                    busyboxFile.getParent()
                                            + ":/system/bin"
                                            + ":/system/xbin"
                                            + ":/vendor/bin"
                            )
                            + ":$PATH; "

                            /*
                             * HOME
                             */
                            + "export HOME=/data/local/tmp; "

                            /*
                             * 临时目录
                             */
                            + "export TMPDIR=/data/local/tmp; "

                            /*
                             * Android 64 位库
                             */
                            + "export LD_LIBRARY_PATH="
                            + "/system/lib64"
                            + ":/vendor/lib64"
                            + ":$LD_LIBRARY_PATH; "

                            /*
                             * 进入 ELF 所在目录。
                             */
                            + "cd "
                            + shellQuote(elfDir)
                            + "; ";

            // ====================================================
            // ELF 命令
            // ====================================================

            String elfCommand =
                    "exec "
                            + shellQuote(
                                    elf.getAbsolutePath()
                            );

            // ====================================================
            // BusyBox script 创建 PTY
            // ====================================================

            String command =

                    env

                            + shellQuote(
                                    busyboxFile
                                            .getAbsolutePath()
                            )

                            + " script -q -c "

                            + shellQuote(
                                    elfCommand
                            )

                            + " /dev/null";

            // ====================================================
            // 创建 root 进程
            // ====================================================

            ProcessBuilder pb =
                    new ProcessBuilder(
                            suCmd,
                            "-c",
                            command
                    );

            /*
             * 不合并 stdout/stderr。
             *
             * 这样可以知道 ELF 有没有真正的错误输出。
             */
            pb.redirectErrorStream(false);

            try {

                pb.directory(
                        new File(elfDir)
                );

            } catch (Exception ignored) {
            }

            process =
                    pb.start();

            final Process currentProcess =
                    process;

            // ====================================================
            // stdin
            // ====================================================

            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    currentProcess
                                            .getOutputStream()
                            )
                    );

            // ====================================================
            // stdout
            // ====================================================

            Thread stdoutThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getInputStream()
                                    );

                            char[] buffer =
                                    new char[1024];

                            int count;

                            while ((count =
                                    reader.read(buffer))
                                    != -1) {

                                if (count <= 0) {
                                    continue;
                                }

                                String raw =
                                        new String(
                                                buffer,
                                                0,
                                                count
                                        );

                                String clean =
                                        cleanElfOutput(
                                                raw
                                        );

                                if (clean.length() > 0) {

                                    runOnUiThread(() ->
                                            appendText(clean)
                                    );
                                }
                            }

                        } catch (Exception ignored) {
                        }
                    });

            // ====================================================
            // stderr
            // ====================================================

            Thread stderrThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getErrorStream()
                                    );

                            char[] buffer =
                                    new char[1024];

                            int count;

                            while ((count =
                                    reader.read(buffer))
                                    != -1) {

                                if (count <= 0) {
                                    continue;
                                }

                                String raw =
                                        new String(
                                                buffer,
                                                0,
                                                count
                                        );

                                String clean =
                                        cleanElfOutput(
                                                raw
                                        );

                                if (clean.length() > 0) {

                                    runOnUiThread(() ->
                                            appendText(clean)
                                    );
                                }
                            }

                        } catch (Exception ignored) {
                        }
                    });

            stdoutThread.start();

            stderrThread.start();

            // ====================================================
            // 等待进程结束
            // ====================================================

            new Thread(() -> {

                try {

                    int exitCode =
                            currentProcess.waitFor();

                    stdoutThread.join(1000);

                    stderrThread.join(1000);

                    writer = null;

                    /*
                     * 不显示：
                     *
                     * [进程已退出，状态码: xxx]
                     *
                     * 保持界面只显示 ELF 内容。
                     */

                } catch (Exception ignored) {

                    writer = null;
                }

            }).start();

        } catch (Exception ignored) {

            writer = null;
        }
    }

    // ============================================================
    // 准备 BusyBox
    // ============================================================

    private boolean extractAndPrepareBusybox() {

        try {

            File tempFile =
                    new File(
                            getFilesDir(),
                            "busybox_temp"
                    );

            // ====================================================
            // 从 assets 提取 BusyBox
            // ====================================================

            try (
                    InputStream is =
                            getAssets().open("busybox");

                    FileOutputStream fos =
                            new FileOutputStream(
                                    tempFile
                            )
            ) {

                byte[] buffer =
                        new byte[8192];

                int len;

                while ((len =
                        is.read(buffer))
                        > 0) {

                    fos.write(
                            buffer,
                            0,
                            len
                    );
                }
            }

            if (!tempFile.exists()
                    || tempFile.length() < 100000) {

                return false;
            }

            // ====================================================
            // 安装到 /data/local/tmp
            // ====================================================

            busyboxFile =
                    new File(
                            "/data/local/tmp/busybox"
                    );

            String src =
                    shellQuote(
                            tempFile
                                    .getAbsolutePath()
                    );

            String dst =
                    shellQuote(
                            busyboxFile
                                    .getAbsolutePath()
                    );

            String installCommand =

                    "rm -f "
                            + dst
                            + " ; "

                            + "cat "
                            + src
                            + " > "
                            + dst
                            + " ; "

                            + "chmod 755 "
                            + dst;

            Process installProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            installCommand
                    )
                            .redirectErrorStream(true)
                            .start();

            installProcess.waitFor();

            // ====================================================
            // 检查 BusyBox script applet
            // ====================================================

            String scriptCheckCommand =
                    dst + " --list";

            Process scriptCheckProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            scriptCheckCommand
                    )
                            .redirectErrorStream(true)
                            .start();

            String appletList =
                    readAll(
                            scriptCheckProcess
                                    .getInputStream()
                    );

            scriptCheckProcess.waitFor();

            boolean hasScript = false;

            for (String applet :
                    appletList.split("\\s+")) {

                if ("script".equals(
                        applet.trim()
                )) {

                    hasScript = true;

                    break;
                }
            }

            return hasScript;

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // 读取 InputStream
    // ============================================================

    private String readAll(
            InputStream inputStream
    ) {

        StringBuilder result =
                new StringBuilder();

        try {

            InputStreamReader reader =
                    new InputStreamReader(
                            inputStream
                    );

            char[] buffer =
                    new char[1024];

            int count;

            while ((count =
                    reader.read(buffer))
                    != -1) {

                if (count > 0) {

                    result.append(
                            buffer,
                            0,
                            count
                    );
                }
            }

        } catch (Exception ignored) {
        }

        return result.toString();
    }

    // ============================================================
    // 清理 ELF 输出
    // ============================================================

    private String cleanElfOutput(
            String text
    ) {

        if (text == null
                || text.length() == 0) {

            return "";
        }

        // ========================================================
        // 删除真正的 ANSI ESC 序列
        //
        // ESC[1;32m
        // ESC[0m
        // ESC[33m
        // ========================================================

        text =
                text.replaceAll(
                        "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                        ""
                );

        // ========================================================
        // 删除已经变成普通文本的 ANSI
        //
        // [1;32m
        // [0m
        // [33m
        // ========================================================

        text =
                text.replaceAll(
                        "\\[(?:[0-9;?]+)m",
                        ""
                );

        // ========================================================
        // 删除指定文字
        // ========================================================

        text =
                text.replace(
                        "公益倒卖死全家",
                        ""
                );

        return text;
    }

    // ============================================================
    // 获取文件名
    // ============================================================

    private String getFileName(Uri uri) {

        String result = null;

        if ("content".equals(
                uri.getScheme()
        )) {

            try (
                    Cursor cursor =
                            getContentResolver()
                                    .query(
                                            uri,
                                            null,
                                            null,
                                            null,
                                            null
                                    )
            ) {

                if (cursor != null
                        && cursor.moveToFirst()) {

                    int nameIndex =
                            cursor.getColumnIndex(
                                    OpenableColumns
                                            .DISPLAY_NAME
                            );

                    if (nameIndex != -1) {

                        result =
                                cursor.getString(
                                        nameIndex
                                );
                    }
                }

            } catch (Exception ignored) {
            }
        }

        if (result == null) {

            result =
                    uri.getPath();

            if (result != null) {

                int cut =
                        result.lastIndexOf('/');

                if (cut != -1) {

                    result =
                            result.substring(
                                    cut + 1
                            );
                }
            }
        }

        return result;
    }

    // ============================================================
    // 保存脚本列表
    // ============================================================

    private void saveScripts() {

        prefs.edit()
                .putStringSet(
                        "scripts",
                        new HashSet<>(
                                scriptList
                        )
                )
                .apply();
    }

    // ============================================================
    // ELF 列表 Adapter
    // ============================================================

    private class ScriptAdapter
            extends ArrayAdapter<String> {

        public ScriptAdapter() {

            super(
                    MainActivity.this,
                    0,
                    scriptList
            );
        }

        @NonNull
        @Override
        public View getView(
                int position,
                View convertView,
                @NonNull ViewGroup parent
        ) {

            if (convertView == null) {

                convertView =
                        LayoutInflater
                                .from(getContext())
                                .inflate(
                                        R.layout.item_script,
                                        parent,
                                        false
                                );
            }

            String path =
                    scriptList.get(position);

            String fileName =
                    new File(path)
                            .getName();

            TextView tvName =
                    convertView.findViewById(
                            R.id.tvScriptName
                    );

            Button btnRun =
                    convertView.findViewById(
                            R.id.btnRun
                    );

            Button btnDelete =
                    convertView.findViewById(
                            R.id.btnDelete
                    );

            tvName.setText(fileName);

            // ====================================================
            // 运行
            // ====================================================

            btnRun.setOnClickListener(
                    v -> runElf(path)
            );

            // ====================================================
            // 删除
            // ====================================================

            btnDelete.setOnClickListener(v -> {

                scriptList.remove(position);

                adapter.notifyDataSetChanged();

                saveScripts();
            });

            return convertView;
        }
    }

    // ============================================================
    // 添加输出到界面
    // ============================================================

    private void appendText(String text) {

        if (text == null
                || text.length() == 0) {

            return;
        }

        runOnUiThread(() -> {

            tvOutput.append(text);

            scrollView.post(() ->
                    scrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        });
    }

    // ============================================================
    // Activity 销毁
    // ============================================================

    @Override
    protected void onDestroy() {

        super.onDestroy();

        try {

            if (writer != null) {
                writer.close();
            }

        } catch (Exception ignored) {
        }

        try {

            if (process != null) {
                process.destroy();
            }

        } catch (Exception ignored) {
        }

        writer = null;

        process = null;
    }
}


