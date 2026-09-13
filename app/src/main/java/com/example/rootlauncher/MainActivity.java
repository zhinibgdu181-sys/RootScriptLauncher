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

    /*
     * BusyBox 必须叫 busybox。
     *
     * 不要改成：
     * /data/local/tmp/root_launcher_busybox
     *
     * 因为 BusyBox 会根据 argv[0] / basename
     * 判断要运行哪个 applet。
     */
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

                        Uri uri =
                                result.getData().getData();

                        if (uri == null) {
                            return;
                        }

                        String displayName =
                                getFileName(uri);

                        if (displayName == null ||
                                displayName.length() == 0) {

                            displayName =
                                    "script_" +
                                            System.currentTimeMillis() +
                                            ".sh";
                        }

                        File destFile =
                                new File(
                                        getFilesDir(),
                                        displayName
                                );

                        try {

                            InputStream is =
                                    getContentResolver()
                                            .openInputStream(uri);

                            if (is == null) {

                                appendText(
                                        "❌ 无法读取文件\n"
                                );

                                return;
                            }

                            FileOutputStream fos =
                                    new FileOutputStream(
                                            destFile
                                    );

                            byte[] buffer =
                                    new byte[8192];

                            int len;

                            while ((len =
                                    is.read(buffer)) > 0) {

                                fos.write(
                                        buffer,
                                        0,
                                        len
                                );
                            }

                            is.close();
                            fos.close();

                            /*
                             * 这里的 chmod 只是方便普通文件权限。
                             *
                             * 真正执行 ELF 时，
                             * 后面仍然通过 root 检查。
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
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        tvOutput =
                findViewById(
                        R.id.tvOutput
                );

        etInput =
                findViewById(
                        R.id.etInput
                );

        scrollView =
                findViewById(
                        R.id.scrollView
                );

        lvScripts =
                findViewById(
                        R.id.lvScripts
                );

        Button btnAdd =
                findViewById(
                        R.id.btnAdd
                );

        Button btnSend =
                findViewById(
                        R.id.btnSend
                );

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

        scriptList.addAll(
                savedScripts
        );

        adapter =
                new ScriptAdapter();

        lvScripts.setAdapter(
                adapter
        );

        /*
         * 添加文件
         */
        btnAdd.setOnClickListener(v -> {

            Intent intent =
                    new Intent(
                            Intent.ACTION_GET_CONTENT
                    );

            intent.setType("*/*");

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            filePickerLauncher.launch(
                    intent
            );
        });

        /*
         * 向正在运行的程序发送输入
         */
        btnSend.setOnClickListener(v -> {

            String input =
                    etInput.getText().toString();

            if (input.length() == 0) {
                return;
            }

            input += "\n";

            BufferedWriter currentWriter =
                    writer;

            if (currentWriter != null) {

                try {

                    currentWriter.write(
                            input
                    );

                    currentWriter.flush();

                    etInput.setText("");

                    appendText(
                            ">>> " +
                                    input
                    );

                } catch (Exception e) {

                    appendText(
                            "❌ 输入失败: " +
                                    e.getMessage() +
                                    "\n"
                    );

                }

            } else {

                appendText(
                        "⚠ 当前没有正在运行的程序\n"
                );
            }
        });

        /*
         * 检查 root
         */
        new Thread(() -> {

            if (!checkRoot()) {

                showRootDialog();

            } else {

                appendText(
                        "√ 已获取 root 权限\n"
                );
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

        return "su";
    }

    /**
     * 检查 root
     */
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

            while ((line =
                    reader.readLine()) != null) {

                output.append(line);
            }

            int exitCode =
                    p.waitFor();

            return exitCode == 0 &&
                    output
                            .toString()
                            .contains("uid=0");

        } catch (Exception e) {

            return false;
        }
    }

    /**
     * Root 提示
     */
    private void showRootDialog() {

        runOnUiThread(() -> {

            new AlertDialog.Builder(
                    MainActivity.this
            )
                    .setTitle(
                            "需要 root 权限"
                    )
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

                                            pendingScriptPath =
                                                    null;

                                            runElfReal(
                                                    path
                                            );
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

    /**
     * Shell 参数转义
     */
    private String shellQuote(
            String value
    ) {

        if (value == null) {

            return "''";
        }

        return "'" +
                value.replace(
                        "'",
                        "'\\''"
                ) +
                "'";
    }

    /**
     * 运行 ELF
     */
    private void runElf(
            String scriptPath
    ) {

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                showRootDialog();

                return;
            }

            appendText(
                    "\n√ 已获取 root 权限\n"
            );

            runElfReal(
                    scriptPath
            );

        }).start();
    }

    /**
     * 实际运行 ELF
     *
     * 使用：
     *
     * su
     *   └── busybox script
     *          └── ELF
     *
     * script 用来提供 PTY。
     */
    private void runElfReal(
            String scriptPath
    ) {

        try {

            File elf =
                    new File(
                            scriptPath
                    );

            if (!elf.exists()) {

                appendText(
                        "❌ 文件不存在:\n" +
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

            /*
             * 先准备 BusyBox
             */
            if (!extractAndPrepareBusybox()) {

                appendText(
                        "❌ BusyBox 准备失败\n"
                );

                return;
            }

            /*
             * ELF 本身位于 app 私有目录。
             *
             * root 可以直接执行。
             */
            String elfPath =
                    shellQuote(
                            elf.getAbsolutePath()
                    );

            /*
             * exec ELF
             *
             * 这样 script 的 shell 最终会被 ELF 替换。
             */
            String elfCommand =
                    "exec " +
                            elfPath;

            /*
             * BusyBox script 创建 PTY。
             *
             * 重要：
             * BusyBox 文件名必须是 busybox。
             */
            String command =
                    shellQuote(
                            busyboxFile
                                    .getAbsolutePath()
                    ) +
                            " script -q -c " +
                            shellQuote(
                                    elfCommand
                            ) +
                            " /dev/null";

            appendText(
                    "√ BusyBox PTY 模式启动\n"
            );

            appendText(
                    "→ " +
                            elf.getAbsolutePath() +
                            "\n"
            );

            ProcessBuilder pb =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    );

            pb.redirectErrorStream(true);

            process =
                    pb.start();

            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream()
                            )
                    );

            /*
             * 读取输出。
             *
             * 不使用 readLine()。
             *
             * 某些 ELF 会输出：
             *
             * printf("xxx")
             *
             * 但不马上输出 \n。
             *
             * readLine() 会一直等，
             * 看起来就像程序卡住。
             *
             * 所以这里按字符块读取。
             */
            final Process currentProcess =
                    process;

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
                            reader.read(
                                    buffer
                            )) != -1) {

                        if (count <= 0) {
                            continue;
                        }

                        final String output =
                                new String(
                                        buffer,
                                        0,
                                        count
                                );

                        runOnUiThread(
                                () ->
                                        appendText(
                                                output
                                        )
                        );
                    }

                    int exitCode =
                            currentProcess.waitFor();

                    writer = null;

                    runOnUiThread(
                            () ->
                                    appendText(
                                            "\n[进程结束，exit=" +
                                                    exitCode +
                                                    "]\n"
                                    )
                    );

                } catch (Exception e) {

                    writer = null;

                    runOnUiThread(
                            () ->
                                    appendText(
                                            "\n❌ 读取进程输出失败: " +
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
     * 提取并安装 BusyBox
     *
     * 注意：
     *
     * 不能使用：
     *
     * /data/local/tmp/root_launcher_busybox
     *
     * 因为 BusyBox 会把它识别成：
     *
     * applet = root_launcher_busybox
     *
     * 最终出现：
     *
     * root_launcher_busybox: applet not found
     *
     * 所以必须使用：
     *
     * /data/local/tmp/busybox
     */
    private boolean extractAndPrepareBusybox() {

        try {

            /*
             * 1. 从 assets 提取
             */
            File tempFile =
                    new File(
                            getFilesDir(),
                            "busybox_temp"
                    );

            try (
                    InputStream is =
                            getAssets().open(
                                    "busybox"
                            );

                    FileOutputStream fos =
                            new FileOutputStream(
                                    tempFile
                            )
            ) {

                byte[] buffer =
                        new byte[8192];

                int len;

                while ((len =
                        is.read(buffer)) > 0) {

                    fos.write(
                            buffer,
                            0,
                            len
                    );
                }
            }

            if (!tempFile.exists() ||
                    tempFile.length() < 100000) {

                appendText(
                        "❌ assets/busybox 文件异常\n"
                );

                return false;
            }

            appendText(
                    "√ BusyBox 已从 assets 提取\n"
            );

            appendText(
                    "→ 大小: " +
                            tempFile.length() +
                            " bytes\n"
            );

            /*
             * 2. 安装位置
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

            /*
             * 3. root 安装
             */
            String installCommand =
                    "rm -f " +
                            dst +
                            " ; " +

                            "cat " +
                            src +
                            " > " +
                            dst +
                            " ; " +

                            "chmod 755 " +
                            dst +
                            " ; " +

                            "ls -l " +
                            dst;

            appendText(
                    "√ 正在安装 BusyBox...\n"
            );

            Process installProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            installCommand
                    )
                            .redirectErrorStream(true)
                            .start();

            String installOutput =
                    readAll(
                            installProcess
                                    .getInputStream()
                    );

            int installExitCode =
                    installProcess.waitFor();

            if (installOutput.length() > 0) {

                appendText(
                        installOutput
                );

                if (!installOutput.endsWith(
                        "\n"
                )) {

                    appendText(
                            "\n"
                    );
                }
            }

            if (installExitCode != 0) {

                appendText(
                        "❌ BusyBox 安装命令失败，exit=" +
                                installExitCode +
                                "\n"
                );

                return false;
            }

            /*
             * 4. root 下检查文件和执行权限
             *
             * 不使用：
             *
             * busyboxFile.canExecute()
             *
             * 因为这个 Java App 本身是普通 UID，
             * Java 层看到的权限不一定代表 root
             * 实际执行权限。
             */
            String checkCommand =
                    "test -f " +
                            dst +
                            " && " +

                            "test -x " +
                            dst +
                            " && " +

                            "echo BUSYBOX_EXEC_OK";

            Process checkProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            checkCommand
                    )
                            .redirectErrorStream(true)
                            .start();

            String checkOutput =
                    readAll(
                            checkProcess
                                    .getInputStream()
                    );

            int checkExitCode =
                    checkProcess.waitFor();

            if (checkOutput.length() > 0) {

                appendText(
                        "ROOT 检查:\n" +
                                checkOutput
                );

                if (!checkOutput.endsWith(
                        "\n"
                )) {

                    appendText(
                            "\n"
                    );
                }
            }

            if (checkExitCode != 0 ||
                    !checkOutput.contains(
                            "BUSYBOX_EXEC_OK"
                    )) {

                appendText(
                        "❌ BusyBox 没有执行权限\n"
                );

                return false;
            }

            /*
             * 5. 实际执行 BusyBox
             */
            String testCommand =
                    dst +
                            " --help";

            Process testProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            testCommand
                    )
                            .redirectErrorStream(true)
                            .start();

            String testOutput =
                    readAll(
                            testProcess
                                    .getInputStream()
                    );

            int testExitCode =
                    testProcess.waitFor();

            if (testExitCode != 0) {

                appendText(
                        "❌ BusyBox 本体无法执行\n"
                );

                if (testOutput.length() > 0) {

                    appendText(
                            testOutput
                    );
                }

                return false;
            }

            /*
             * 6. 确认 script applet
             */
            String scriptCheckCommand =
                    dst +
                            " --list";

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

            int appletExitCode =
                    scriptCheckProcess.waitFor();

            if (appletExitCode != 0) {

                appendText(
                        "❌ 无法读取 BusyBox applet 列表\n"
                );

                return false;
            }

            boolean hasScript =
                    false;

            String[] applets =
                    appletList.split(
                            "\\s+"
                    );

            for (String applet :
                    applets) {

                if ("script".equals(
                        applet.trim()
                )) {

                    hasScript = true;
                    break;
                }
            }

            if (!hasScript) {

                appendText(
                        "❌ 当前 BusyBox 不包含 script applet\n"
                );

                appendText(
                        "→ 无法创建 PTY\n"
                );

                return false;
            }

            appendText(
                    "√ BusyBox 执行测试成功\n"
            );

            appendText(
                    "√ script PTY applet 可用\n"
            );

            return true;

        } catch (Exception e) {

            appendText(
                    "❌ BusyBox 准备异常: " +
                            e.getMessage() +
                            "\n"
            );

            return false;
        }
    }

    /**
     * 读取进程全部输出
     */
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
                    reader.read(buffer)) != -1) {

                if (count > 0) {

                    result.append(
                            buffer,
                            0,
                            count
                    );
                }
            }

        } catch (Exception e) {

            result.append(
                    "\n[读取输出失败: "
            );

            result.append(
                    e.getMessage()
            );

            result.append(
                    "]\n"
            );
        }

        return result.toString();
    }

    /**
     * 获取文件名
     */
    private String getFileName(
            Uri uri
    ) {

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

                if (cursor != null &&
                        cursor.moveToFirst()) {

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

            } catch (Exception e) {

                e.printStackTrace();
            }
        }

        if (result == null) {

            result =
                    uri.getPath();

            if (result != null) {

                int cut =
                        result.lastIndexOf(
                                '/'
                        );

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
                        new HashSet<>(
                                scriptList
                        )
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
                                .from(
                                        getContext()
                                )
                                .inflate(
                                        R.layout.item_script,
                                        parent,
                                        false
                                );
            }

            String path =
                    scriptList.get(
                            position
                    );

            String fileName =
                    new File(
                            path
                    ).getName();

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

            tvName.setText(
                    fileName
            );

            btnRun.setOnClickListener(
                    v -> runElf(path)
            );

            btnDelete.setOnClickListener(
                    v -> {

                        scriptList.remove(
                                position
                        );

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
    private void appendText(
            String text
    ) {

        runOnUiThread(() -> {

            tvOutput.append(
                    text
            );

            scrollView.post(
                    () ->
                            scrollView.fullScroll(
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

        writer = null;
        process = null;
    }
}


