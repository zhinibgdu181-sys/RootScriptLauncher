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

    private final ArrayList<String> scriptList =
            new ArrayList<>();

    private ScriptAdapter adapter;

    private volatile Process process;
    private volatile BufferedWriter writer;

    private volatile boolean processRunning = false;

    private String pendingScriptPath = null;

    private android.content.SharedPreferences prefs;

    /*
     * BusyBox 固定使用这个名字。
     *
     * 不要改成：
     *
     * root_launcher_busybox
     *
     * BusyBox 是 multicall binary，
     * argv[0] 会影响 applet 分发。
     */
    private File busyboxFile =
            new File("/data/local/tmp/busybox");

    /*
     * 防止同时启动多个 ELF。
     */
    private final Object processLock =
            new Object();

    /*
     * ============================================================
     * 文件选择器
     * ============================================================
     */

    private final androidx.activity.result.ActivityResultLauncher<Intent>
            filePickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract
                            .ActivityResultContracts
                            .StartActivityForResult(),
                    result -> {

                        if (result.getResultCode()
                                != Activity.RESULT_OK) {
                            return;
                        }

                        Intent data =
                                result.getData();

                        if (data == null) {
                            return;
                        }

                        Uri uri =
                                data.getData();

                        if (uri == null) {
                            return;
                        }

                        importFile(uri);
                    }
            );

    /*
     * ============================================================
     * Activity
     * ============================================================
     */

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(
                savedInstanceState
        );

        setContentView(
                R.layout.activity_main
        );

        initViews();

        initPreferences();

        initList();

        initButtons();

        checkRootAsync();
    }

    /*
     * ============================================================
     * 初始化
     * ============================================================
     */

    private void initViews() {

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
    }

    private void initPreferences() {

        prefs =
                getSharedPreferences(
                        "script_prefs",
                        MODE_PRIVATE
                );
    }

    private void initList() {

        Set<String> savedScripts =
                prefs.getStringSet(
                        "scripts",
                        new HashSet<>()
                );

        scriptList.clear();

        /*
         * 只保留当前实际存在的文件。
         */
        for (String path :
                savedScripts) {

            if (path == null) {
                continue;
            }

            File file =
                    new File(path);

            if (file.exists()) {

                scriptList.add(path);
            }
        }

        adapter =
                new ScriptAdapter();

        lvScripts.setAdapter(
                adapter
        );

        saveScripts();
    }

    private void initButtons() {

        Button btnAdd =
                findViewById(
                        R.id.btnAdd
                );

        Button btnSend =
                findViewById(
                        R.id.btnSend
                );

        /*
         * 添加 ELF / 脚本
         */
        btnAdd.setOnClickListener(
                v -> openFilePicker()
        );

        /*
         * 向 PTY 发送输入
         */
        btnSend.setOnClickListener(
                v -> sendInput()
        );
    }

    /*
     * ============================================================
     * 文件选择
     * ============================================================
     */

    private void openFilePicker() {

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
    }

    private void importFile(
            Uri uri
    ) {

        String displayName =
                getFileName(uri);

        if (displayName == null ||
                displayName.length() == 0) {

            displayName =
                    "script_" +
                            System.currentTimeMillis() +
                            ".sh";
        }

        /*
         * 防止恶意/奇怪文件名造成路径问题。
         *
         * 这里只保留最后的文件名。
         */
        displayName =
                new File(
                        displayName
                ).getName();

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

            try (
                    InputStream input =
                            is;

                    FileOutputStream output =
                            new FileOutputStream(
                                    destFile
                            )
            ) {

                byte[] buffer =
                        new byte[8192];

                int len;

                while ((len =
                        input.read(buffer)) > 0) {

                    output.write(
                            buffer,
                            0,
                            len
                    );
                }
            }

            /*
             * 设置普通执行权限。
             */
            Process chmod =
                    new ProcessBuilder(
                            "chmod",
                            "755",
                            destFile
                                    .getAbsolutePath()
                    )
                            .redirectErrorStream(true)
                            .start();

            chmod.waitFor();

            String path =
                    destFile.getAbsolutePath();

            /*
             * 防止同一个文件重复添加。
             */
            if (!scriptList.contains(path)) {

                scriptList.add(path);

                adapter.notifyDataSetChanged();

                saveScripts();

            } else {

                appendText(
                        "⚠ 文件已经存在列表中\n"
                );

                return;
            }

            appendText(
                    "√ 已添加: " +
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

    /*
     * ============================================================
     * Root
     * ============================================================
     */

    private String findSu() {

        String[] suPaths = {

                "/system/bin/su",

                "/system/xbin/su",

                "/sbin/su",

                "/debug_ramdisk/su"
        };

        for (String path :
                suPaths) {

            if (new File(path).exists()) {

                return path;
            }
        }

        /*
         * KernelSU / Magisk 通常会把 su
         * 放到 PATH 中。
         */
        return "su";
    }

    private boolean checkRoot() {

        try {

            Process process =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "id"
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            process
                                    .getInputStream()
                    );

            int exitCode =
                    process.waitFor();

            return exitCode == 0 &&
                    output.contains(
                            "uid=0"
                    );

        } catch (Exception e) {

            return false;
        }
    }

    private void checkRootAsync() {

        new Thread(() -> {

            if (checkRoot()) {

                appendText(
                        "√ 已获取 root 权限\n"
                );

            } else {

                showRootDialog();
            }

        }).start();
    }

    private void showRootDialog() {

        runOnUiThread(() -> {

            if (isFinishing()) {
                return;
            }

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

                                        if (pendingScriptPath
                                                != null) {

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

    /*
     * ============================================================
     * Shell 工具
     * ============================================================
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

    /*
     * ============================================================
     * ELF 启动
     * ============================================================
     */

    private void runElf(
            String scriptPath
    ) {

        if (processRunning) {

            appendText(
                    "⚠ 已经有程序正在运行\n"
            );

            return;
        }

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                showRootDialog();

                return;
            }

            runElfReal(
                    scriptPath
            );

        }).start();
    }

    private void runElfReal(
            String scriptPath
    ) {

        synchronized (processLock) {

            if (processRunning) {

                appendText(
                        "⚠ 已经有程序正在运行\n"
                );

                return;
            }

            processRunning = true;
        }

        try {

            File elf =
                    new File(
                            scriptPath
                    );

            /*
             * 文件检查
             */
            if (!elf.exists()) {

                appendText(
                        "❌ 文件不存在:\n" +
                                scriptPath +
                                "\n"
                );

                return;
            }

            if (!elf.isFile()) {

                appendText(
                        "❌ 目标不是普通文件\n"
                );

                return;
            }

            if (elf.length() == 0) {

                appendText(
                        "❌ 文件为空\n"
                );

                return;
            }

            appendText(
                    "\n√ 准备运行 ELF...\n"
            );

            appendText(
                    "$ " +
                            elf.getName() +
                            "\n"
            );

            /*
             * 检查 root
             */
            if (!checkRoot()) {

                appendText(
                        "❌ root 权限检查失败\n"
                );

                return;
            }

            /*
             * 准备 BusyBox。
             */
            if (!extractAndPrepareBusybox()) {

                appendText(
                        "❌ BusyBox 准备失败\n"
                );

                return;
            }

            String elfPath =
                    shellQuote(
                            elf.getAbsolutePath()
                    );

            /*
             * script 的 -c 参数执行：
             *
             * exec '/data/.../ELF'
             *
             * 让 ELF 成为 PTY 中的实际程序。
             */
            String elfCommand =
                    "exec " +
                            elfPath;

            /*
             * 最终：
             *
             * su -c
             *   /data/local/tmp/busybox
             *   script
             *   -q
             *   -c
             *   'exec /data/.../ELF'
             *   /dev/null
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
                    "√ 使用 root + PTY 启动\n"
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

            /*
             * stdout + stderr 合并。
             */
            pb.redirectErrorStream(
                    true
            );

            process =
                    pb.start();

            /*
             * stdin
             *
             * App -> su -> script -> PTY -> ELF
             */
            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process
                                            .getOutputStream()
                            )
                    );

            final Process currentProcess =
                    process;

            /*
             * 实时读取输出。
             *
             * 不使用 readLine()。
             */
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

                        final String output =
                                new String(
                                        buffer,
                                        0,
                                        count
                                );

                        appendText(
                                output
                        );
                    }

                    int exitCode =
                            currentProcess
                                    .waitFor();

                    appendText(
                            "\n[进程已退出，状态码: " +
                                    exitCode +
                                    "]\n"
                    );

                } catch (Exception e) {

                    appendText(
                            "\n❌ 读取输出失败: " +
                                    e.getMessage() +
                                    "\n"
                    );

                } finally {

                    writer = null;
                    process = null;
                    processRunning = false;
                }

            }).start();

        } catch (Exception e) {

            appendText(
                    "❌ ELF 执行异常: " +
                            e.getMessage() +
                            "\n"
            );

            process = null;
            writer = null;
            processRunning = false;
        }
    }

    /*
     * ============================================================
     * BusyBox
     * ============================================================
     */

    private boolean extractAndPrepareBusybox() {

        try {

            /*
             * 1.
             * 从 assets/busybox 提取到 App 私有目录。
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

            /*
             * 2.
             * root 安装位置。
             */
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

            /*
             * 3.
             * 删除旧 BusyBox，
             * 重新复制，
             * chmod 755。
             *
             * 最后输出 ls -l，
             * 方便以后诊断。
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

            Process installProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            installCommand
                    )
                            .redirectErrorStream(
                                    true
                            )
                            .start();

            String installOutput =
                    readAll(
                            installProcess
                                    .getInputStream()
                    );

            int installExitCode =
                    installProcess.waitFor();

            if (installExitCode != 0) {

                appendText(
                        "❌ BusyBox 安装失败\n"
                );

                if (installOutput.length() > 0) {

                    appendText(
                            installOutput +
                                    "\n"
                    );
                }

                return false;
            }

            /*
             * 4.
             * root 下检查：
             *
             * 文件存在
             * 文件可执行
             */
            String permissionCommand =
                    "test -f " +
                            dst +
                            " && " +

                            "test -x " +
                            dst +
                            " && " +

                            "echo BUSYBOX_EXEC_OK";

            Process permissionProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            permissionCommand
                    )
                            .redirectErrorStream(
                                    true
                            )
                            .start();

            String permissionOutput =
                    readAll(
                            permissionProcess
                                    .getInputStream()
                    );

            int permissionExitCode =
                    permissionProcess
                            .waitFor();

            if (permissionExitCode != 0 ||
                    !permissionOutput.contains(
                            "BUSYBOX_EXEC_OK"
                    )) {

                appendText(
                        "❌ BusyBox 没有执行权限\n"
                );

                if (permissionOutput.length() > 0) {

                    appendText(
                            permissionOutput +
                                    "\n"
                    );
                }

                return false;
            }

            /*
             * 5.
             * 实际执行 BusyBox。
             *
             * 这里比 canExecute() 更可靠。
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
                            .redirectErrorStream(
                                    true
                            )
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
                            testOutput +
                                    "\n"
                    );
                }

                return false;
            }

            /*
             * 6.
             * 检查 script applet。
             */
            String listCommand =
                    dst +
                            " --list";

            Process listProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            listCommand
                    )
                            .redirectErrorStream(
                                    true
                            )
                            .start();

            String appletList =
                    readAll(
                            listProcess
                                    .getInputStream()
                    );

            int listExitCode =
                    listProcess.waitFor();

            if (listExitCode != 0) {

                appendText(
                        "❌ 无法读取 BusyBox applet\n"
                );

                return false;
            }

            boolean hasScript =
                    containsApplet(
                            appletList,
                            "script"
                    );

            if (!hasScript) {

                appendText(
                        "❌ 当前 BusyBox 不包含 script applet\n"
                );

                appendText(
                        "→ 无法创建 PTY\n"
                );

                return false;
            }

            /*
             * 7.
             * 检查 script --help，
             * 确认这个 applet 本身可以运行。
             */
            String scriptHelpCommand =
                    dst +
                            " script --help";

            Process scriptHelpProcess =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            scriptHelpCommand
                    )
                            .redirectErrorStream(
                                    true
                            )
                            .start();

            String scriptHelp =
                    readAll(
                            scriptHelpProcess
                                    .getInputStream()
                    );

            int scriptHelpExit =
                    scriptHelpProcess
                            .waitFor();

            /*
             * 某些 BusyBox 版本的
             * script --help 返回非 0，
             * 所以这里不把它作为硬失败条件。
             *
             * 只要 applet 存在即可继续。
             */
            if (scriptHelpExit != 0 &&
                    scriptHelp.length() == 0) {

                appendText(
                        "⚠ script applet 响应异常，继续尝试\n"
                );
            }

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

    private boolean containsApplet(
            String list,
            String target
    ) {

        if (list == null) {
            return false;
        }

        String[] applets =
                list.split(
                        "\\s+"
                );

        for (String applet :
                applets) {

            if (target.equals(
                    applet.trim()
            )) {

                return true;
            }
        }

        return false;
    }

    /*
     * ============================================================
     * 输入
     * ============================================================
     */

    private void sendInput() {

        String input =
                etInput
                        .getText()
                        .toString();

        if (input.length() == 0) {
            return;
        }

        BufferedWriter currentWriter =
                writer;

        if (currentWriter == null ||
                !processRunning) {

            appendText(
                    "⚠ 当前没有正在运行的程序\n"
            );

            return;
        }

        try {

            currentWriter.write(
                    input
            );

            currentWriter.write(
                    "\n"
            );

            currentWriter.flush();

            etInput.setText("");

            appendText(
                    ">>> " +
                            input +
                            "\n"
            );

        } catch (Exception e) {

            appendText(
                    "❌ 输入失败: " +
                            e.getMessage() +
                            "\n"
            );
        }
    }

    /*
     * ============================================================
     * IO
     * ============================================================
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
                    new char[2048];

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

        } catch (Exception e) {

            /*
             * 这里不抛异常，
             * 避免影响 root / BusyBox 流程。
             */
        }

        return result.toString();
    }

    /*
     * ============================================================
     * 文件名
     * ============================================================
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

    /*
     * ============================================================
     * 保存列表
     * ============================================================
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

    /*
     * ============================================================
     * Script Adapter
     * ============================================================
     */

    private class ScriptAdapter
            extends ArrayAdapter<String> {

        ScriptAdapter() {

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

            /*
             * position 可能因为删除操作变化，
             * 所以点击时重新获取位置。
             */
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

            String path =
                    scriptList.get(
                            position
                    );

            String fileName =
                    new File(
                            path
                    ).getName();

            tvName.setText(
                    fileName
            );

            btnRun.setOnClickListener(
                    v -> {

                        int currentPosition =
                                lvScripts
                                        .getPositionForView(
                                                convertView
                                        );

                        if (currentPosition < 0 ||
                                currentPosition >=
                                        scriptList.size()) {

                            return;
                        }

                        runElf(
                                scriptList.get(
                                        currentPosition
                                )
                        );
                    }
            );

            btnDelete.setOnClickListener(
                    v -> {

                        int currentPosition =
                                lvScripts
                                        .getPositionForView(
                                                convertView
                                        );

                        if (currentPosition < 0 ||
                                currentPosition >=
                                        scriptList.size()) {

                            return;
                        }

                        String deletePath =
                                scriptList.remove(
                                        currentPosition
                                );

                        File deleteFile =
                                new File(
                                        deletePath
                                );

                        if (deleteFile.exists()) {

                            /*
                             * 只删除 App 自己导入到
                             * getFilesDir() 的文件。
                             */
                            deleteFile.delete();
                        }

                        notifyDataSetChanged();

                        saveScripts();

                        appendText(
                                "X 已移除: " +
                                        fileName +
                                        "\n"
                        );
                    }
            );

            return convertView;
        }
    }

    /*
     * ============================================================
     * 输出
     * ============================================================
     */

    private void appendText(
            String text
    ) {

        if (text == null) {
            return;
        }

        runOnUiThread(() -> {

            if (tvOutput == null) {
                return;
            }

            tvOutput.append(
                    text
            );

            if (scrollView != null) {

                scrollView.post(
                        () ->
                                scrollView.fullScroll(
                                        View.FOCUS_DOWN
                                )
                );
            }
        });
    }

    /*
     * ============================================================
     * 生命周期
     * ============================================================
     */

    @Override
    protected void onDestroy() {

        /*
         * 先停止程序，
         * 再释放 Activity。
         */
        try {

            BufferedWriter currentWriter =
                    writer;

            if (currentWriter != null) {

                currentWriter.close();
            }

        } catch (Exception ignored) {
        }

        try {

            Process currentProcess =
                    process;

            if (currentProcess != null) {

                currentProcess.destroy();
            }

        } catch (Exception ignored) {
        }

        writer = null;
        process = null;
        processRunning = false;

        super.onDestroy();
    }
}


