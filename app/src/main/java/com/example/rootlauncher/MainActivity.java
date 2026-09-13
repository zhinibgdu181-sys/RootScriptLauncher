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

    private Process process;
    private BufferedWriter writer;

    private String pendingScriptPath = null;

    private android.content.SharedPreferences prefs;

    // 可选 BusyBox，只用于需要 TTY 的场景
    private File busyboxFile;

    private final androidx.activity.result.ActivityResultLauncher<Intent>
            filePickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts
                            .StartActivityForResult(),
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
                                    "script_" +
                                    System.currentTimeMillis() +
                                    ".sh";
                        }

                        File destFile =
                                new File(getFilesDir(), displayName);

                        try {

                            InputStream is =
                                    getContentResolver()
                                            .openInputStream(uri);

                            if (is == null) {
                                appendText("❌ 无法读取文件\n");
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

                            // chmod 755
                            Process chmod =
                                    new ProcessBuilder(
                                            "chmod",
                                            "755",
                                            destFile.getAbsolutePath()
                                    ).start();

                            chmod.waitFor();

                            scriptList.add(destFile.getAbsolutePath());

                            adapter.notifyDataSetChanged();

                            saveScripts();

                            appendText(
                                    "√ 已添加脚本: " +
                                    displayName +
                                    "\n"
                            );

                        } catch (Exception e) {

                            appendText(
                                    "❌ 导入失败: " +
                                    e.getMessage() +
                                    "\n"
                            );
                        }
                    }
            );

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

        // 添加文件
        btnAdd.setOnClickListener(v -> {

            Intent intent =
                    new Intent(Intent.ACTION_GET_CONTENT);

            intent.setType("*/*");

            intent.addCategory(Intent.CATEGORY_OPENABLE);

            filePickerLauncher.launch(intent);
        });

        // 向正在运行的 ELF 发送输入
        btnSend.setOnClickListener(v -> {

            String input =
                    etInput.getText().toString();

            if (input.length() == 0) {
                return;
            }

            input += "\n";

            if (writer != null) {

                try {

                    writer.write(input);
                    writer.flush();

                    etInput.setText("");

                    appendText(">>> " + input);

                } catch (Exception e) {

                    appendText(
                            "❌ 输入失败: " +
                            e.getMessage() +
                            "\n"
                    );
                }

            } else {

                appendText("⚠ 当前没有正在运行的程序\n");
            }
        });

        // 检查 Root
        new Thread(() -> {

            if (!checkRoot()) {
                showRootDialog();
            } else {
                appendText("√ 已获取 root 权限\n");
            }

        }).start();
    }

    /**
     * 查找 su
     */
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

        // KernelSU / Magisk 等通常可以直接通过 PATH 找到
        return "su";
    }

    /**
     * 检查 Root
     */
    private boolean checkRoot() {

        try {

            String suCmd = findSu();

            Process p =
                    new ProcessBuilder(
                            suCmd,
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

            int exitCode = p.waitFor();

            return exitCode == 0 &&
                    output.toString().contains("uid=0");

        } catch (Exception e) {

            return false;
        }
    }

    /**
     * Root 提示
     */
    private void showRootDialog() {

        runOnUiThread(() -> {

            new AlertDialog.Builder(MainActivity.this)

                    .setTitle("需要 root 权限")

                    .setMessage(
                            "本软件需要 root 权限才能执行 ELF。\n\n" +
                            "请在 KernelSU / Magisk 中允许本应用，" +
                            "然后点击「重试」。"
                    )

                    .setPositiveButton(
                            "重试",
                            (dialog, which) -> {

                                new Thread(() -> {

                                    if (checkRoot()) {

                                        appendText(
                                                "√ 已获取 root 权限\n"
                                        );

                                        if (pendingScriptPath != null) {

                                            String path =
                                                    pendingScriptPath;

                                            pendingScriptPath = null;

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
                            (dialog, which) -> finish()
                    )

                    .setCancelable(false)

                    .show();
        });
    }

    /**
     * 对 shell 参数进行安全转义
     */
    private String shellQuote(String value) {

        if (value == null) {
            return "''";
        }

        return "'" +
                value.replace("'", "'\\''") +
                "'";
    }

    /**
     * 运行 ELF
     *
     * 核心修复：
     *
     * 不再：
     *
     * /data/local/tmp/root_launcher_busybox script ...
     *
     * 而是直接：
     *
     * su -c /path/to/ELF
     *
     * 这样不会触发：
     *
     * root_launcher_busybox: applet not found
     */
    private void runElf(String scriptPath) {

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath = scriptPath;

                showRootDialog();

                return;
            }

            appendText("\n√ 已获取 root 权限\n");

            runElfReal(scriptPath);

        }).start();
    }

    /**
     * 实际执行 ELF
     */
    private void runElfReal(String scriptPath) {

        try {

            File elf = new File(scriptPath);

            if (!elf.exists()) {

                appendText(
                        "❌ 文件不存在:\n" +
                        scriptPath +
                        "\n"
                );

                return;
            }

            if (!elf.canRead()) {

                appendText(
                        "❌ 文件无法读取:\n" +
                        scriptPath +
                        "\n"
                );

                return;
            }

            appendText(
                    "√ 准备运行 ELF...\n"
            );

            appendText(
                    "$ " +
                    elf.getName() +
                    "\n"
            );

            String suCmd = findSu();

            /*
             * 直接执行 ELF。
             *
             * 注意：
             * 这里不再经过 BusyBox script。
             *
             * 对于你的静态 ARM64 ELF，
             * 这是最直接的执行路径。
             */
            String command =
                    "exec " +
                    shellQuote(
                            elf.getAbsolutePath()
                    );

            appendText(
                    "√ 使用 root 直接启动 ELF\n"
            );

            appendText(
                    "→ " +
                    elf.getAbsolutePath() +
                    "\n"
            );

            ProcessBuilder pb =
                    new ProcessBuilder(
                            suCmd,
                            "-c",
                            command
                    );

            pb.redirectErrorStream(true);

            process = pb.start();

            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream()
                            )
                    );

            /*
             * 读取 ELF stdout/stderr
             */
            new Thread(() -> {

                try {

                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream()
                                    )
                            );

                    String line;

                    while ((line = reader.readLine()) != null) {

                        final String output = line;

                        runOnUiThread(
                                () -> appendText(
                                        output + "\n"
                                )
                        );
                    }

                    int exitCode =
                            process.waitFor();

                    runOnUiThread(
                            () -> appendText(
                                    "\n[进程结束，exit=" +
                                    exitCode +
                                    "]\n"
                            )
                    );

                } catch (Exception e) {

                    runOnUiThread(
                            () -> appendText(
                                    "❌ 读取进程输出失败: " +
                                    e.getMessage() +
                                    "\n"
                            )
                    );
                }

            }).start();

        } catch (Exception e) {

            appendText(
                    "❌ ELF 执行异常: " +
                    e.getMessage() +
                    "\n"
            );
        }
    }

    /**
     * 可选：
     * 如果以后确实需要 BusyBox，
     * 文件必须叫 busybox，
     * 不要叫 root_launcher_busybox。
     */
    private boolean extractAndPrepareBusybox() {

        try {

            File tempFile =
                    new File(
                            getFilesDir(),
                            "busybox_temp"
                    );

            InputStream is =
                    getAssets().open("busybox");

            FileOutputStream fos =
                    new FileOutputStream(
                            tempFile
                    );

            byte[] buffer = new byte[8192];

            int len;

            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }

            is.close();
            fos.close();

            if (tempFile.length() < 100000) {

                appendText(
                        "❌ assets/busybox 文件异常\n"
                );

                return false;
            }

            /*
             * 关键：
             * 不要叫 root_launcher_busybox
             */
            busyboxFile =
                    new File(
                            "/data/local/tmp/busybox"
                    );

            String src =
                    shellQuote(
                            tempFile.getAbsolutePath()
                    );

            String dst =
                    shellQuote(
                            busyboxFile.getAbsolutePath()
                    );

            String cmd =
                    "cat " +
                    src +
                    " > " +
                    dst +
                    " && chmod 755 " +
                    dst;

            Process p =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            cmd
                    )
                    .redirectErrorStream(true)
                    .start();

            int exitCode =
                    p.waitFor();

            if (exitCode != 0) {

                appendText(
                        "❌ BusyBox 安装失败，exit=" +
                        exitCode +
                        "\n"
                );

                return false;
            }

            return busyboxFile.exists() &&
                    busyboxFile.length() > 100000 &&
                    busyboxFile.canExecute();

        } catch (Exception e) {

            appendText(
                    "❌ BusyBox 提取异常: " +
                    e.getMessage() +
                    "\n"
            );

            return false;
        }
    }

    /**
     * 获取文件名
     */
    private String getFileName(Uri uri) {

        String result = null;

        if ("content".equals(uri.getScheme())) {

            try (
                    Cursor cursor =
                            getContentResolver().query(
                                    uri,
                                    null,
                                    null,
                                    null,
                                    null
                            )
            ) {

                if (cursor != null &&
                        cursor.moveToFirst()) {

                    int nameIndex =
                            cursor.getColumnIndex(
                                    OpenableColumns.DISPLAY_NAME
                            );

                    if (nameIndex != -1) {

                        result =
                                cursor.getString(
                                        nameIndex
                                );
                    }
                }

            } catch (Exception e) {

                e.printStackTrace();
            }
        }

        if (result == null) {

            result = uri.getPath();

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

    /**
     * 保存脚本列表
     */
    private void saveScripts() {

        prefs.edit()
                .putStringSet(
                        "scripts",
                        new HashSet<>(scriptList)
                )
                .apply();
    }

    /**
     * 脚本列表 Adapter
     */
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
                    new File(path).getName();

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

            btnRun.setOnClickListener(
                    v -> {

                        new Thread(
                                () -> runElf(path)
                        ).start();
                    }
            );

            btnDelete.setOnClickListener(
                    v -> {

                        scriptList.remove(position);

                        adapter.notifyDataSetChanged();

                        saveScripts();

                        appendText(
                                "X 已移除脚本: " +
                                fileName +
                                "\n"
                        );
                    }
            );

            return convertView;
        }
    }

    /**
     * 输出到 TextView
     */
    private void appendText(String text) {

        runOnUiThread(() -> {

            tvOutput.append(text);

            scrollView.post(
                    () -> scrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        });
    }

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
    }
}
