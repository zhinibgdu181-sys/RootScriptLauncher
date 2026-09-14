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

    private final ArrayList<String> scriptList =
            new ArrayList<>();

    private ScriptAdapter adapter;

    /*
     * 当前 ELF 进程
     */
    private volatile Process process;

    /*
     * ELF stdin
     */
    private volatile BufferedWriter writer;

    /*
     * ELF 是否正在运行
     */
    private volatile boolean elfRunning = false;

    /*
     * Root 不可用时暂存要运行的 ELF
     */
    private String pendingScriptPath = null;

    private android.content.SharedPreferences prefs;

    /*
     * 安装后的 BusyBox
     */
    private File busyboxFile;


    // ============================================================
    // 键盘状态
    // ============================================================

    private boolean keyboardVisible = false;

    /*
     * 键盘打开时，脚本列表压缩到这个高度。
     *
     * 120dp 可以保留两个左右的脚本项目，
     * 同时把更多空间让给终端。
     */
    private static final int SCRIPT_LIST_KEYBOARD_DP = 120;


    // ============================================================
    // 内置 ELF 文件名
    // ============================================================

    private static final String BUILTIN_KAIROS =
            "Kairos_Driver_Loader_Release_90f76e9.sh";

    private static final String BUILTIN_TIME =
            "TIME_Cloud_Loader_Release_1732727.sh";


    // ============================================================
    // 文件选择器
    // ============================================================

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

                        if (displayName == null
                                || displayName.length() == 0) {

                            displayName =
                                    "script_"
                                            + System.currentTimeMillis()
                                            + ".sh";
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
                                return;
                            }

                            FileOutputStream fos =
                                    new FileOutputStream(
                                            destFile
                                    );

                            byte[] buffer =
                                    new byte[8192];

                            int len;

                            while ((len = is.read(buffer)) > 0) {

                                fos.write(
                                        buffer,
                                        0,
                                        len
                                );
                            }

                            is.close();
                            fos.close();


                            /*
                             * 给 ELF / 脚本执行权限
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
                                    destFile
                                            .getAbsolutePath();

                            if (!scriptList.contains(path)) {

                                scriptList.add(path);
                            }

                            adapter.notifyDataSetChanged();

                            saveScripts();

                        } catch (Exception e) {

                            appendText(
                                    "添加文件失败\n"
                            );
                        }
                    }
            );


    // ============================================================
    // onCreate
    // ============================================================

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);


        /*
         * 关键：
         *
         * 键盘出现时，让 Activity 使用
         * adjustResize，而不是让键盘覆盖界面。
         */
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );


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


        // ========================================================
        // SharedPreferences
        // ========================================================

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


        // ========================================================
        // 加载内置 ELF
        // ========================================================

        addBuiltinScript(
                BUILTIN_KAIROS
        );

        addBuiltinScript(
                BUILTIN_TIME
        );


        // ========================================================
        // Adapter
        // ========================================================

        adapter =
                new ScriptAdapter();

        lvScripts.setAdapter(
                adapter
        );


        // ========================================================
        // 键盘监听
        // ========================================================

        setupKeyboardListener();


        // ========================================================
        // 添加文件
        // ========================================================

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


        // ========================================================
        // 发送输入
        // ========================================================

        btnSend.setOnClickListener(v -> {

            String input =
                    etInput
                            .getText()
                            .toString();

            if (input.length() == 0) {
                return;
            }


            /*
             * ELF 正在运行：
             *
             * 输入进入 ELF stdin。
             */
            if (elfRunning
                    && process != null
                    && writer != null) {

                sendInputToElf(
                        input
                );

                return;
            }


            /*
             * 没有 ELF 运行时，
             * 执行 root 命令。
             */
            executeCommand(
                    input
            );
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
    // 键盘监听
    // ============================================================

    private void setupKeyboardListener() {

        final View rootView =
                findViewById(
                        android.R.id.content
                );


        rootView.getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> {

                    if (lvScripts == null) {
                        return;
                    }


                    Rect visibleRect =
                            new Rect();


                    rootView.getWindowVisibleDisplayFrame(
                            visibleRect
                    );


                    int rootHeight =
                            rootView
                                    .getRootView()
                                    .getHeight();


                    int visibleHeight =
                            visibleRect.bottom
                                    - visibleRect.top;


                    int keyboardHeight =
                            rootHeight
                                    - visibleHeight;


                    /*
                     * 键盘高度超过屏幕 15%，
                     * 认为软键盘已经弹出。
                     */
                    boolean nowVisible =
                            keyboardHeight
                                    > rootHeight * 0.15;


                    /*
                     * 状态没有变化，不重复刷新布局。
                     */
                    if (nowVisible
                            == keyboardVisible) {

                        return;
                    }


                    keyboardVisible =
                            nowVisible;


                    if (keyboardVisible) {

                        /*
                         * ==================================================
                         * 键盘弹出
                         *
                         * 脚本列表：
                         *
                         * 55% → 120dp
                         *
                         * 释放出来的空间给终端。
                         * ==================================================
                         */

                        setScriptListKeyboardMode(
                                true
                        );

                    } else {

                        /*
                         * ==================================================
                         * 键盘关闭
                         *
                         * 恢复原来的 55%。
                         * ==================================================
                         */

                        setScriptListKeyboardMode(
                                false
                        );
                    }
                });
    }


    // ============================================================
    // 修改脚本列表高度
    // ============================================================

    private void setScriptListKeyboardMode(
            boolean keyboardMode
    ) {

        if (lvScripts == null) {
            return;
        }


        ViewGroup.LayoutParams rawParams =
                lvScripts.getLayoutParams();


        if (!(rawParams instanceof ConstraintLayout.LayoutParams)) {
            return;
        }


        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams)
                        rawParams;


        if (keyboardMode) {

            /*
             * 键盘打开：
             *
             * 固定 ListView 高度。
             */
            params.height =
                    dpToPx(
                            SCRIPT_LIST_KEYBOARD_DP
                    );


            /*
             * 取消 ConstraintLayout 的
             * 百分比高度约束。
             */
            params.matchConstraintPercentHeight =
                    -1f;


        } else {

            /*
             * 键盘关闭：
             *
             * 恢复原来的：
             *
             * height = 0dp
             * height_percent = 55%
             */
            params.height =
                    0;


            params.matchConstraintPercentHeight =
                    0.55f;
        }


        lvScripts.setLayoutParams(
                params
        );


        lvScripts.requestLayout();


        /*
         * 键盘打开以后，
         * 自动把终端滚到底部。
         */
        if (keyboardMode
                && scrollView != null) {

            scrollView.post(() ->
                    scrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        }
    }


    // ============================================================
    // dp 转 px
    // ============================================================

    private int dpToPx(
            int dp
    ) {

        return (int) (
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density
                        + 0.5f
        );
    }


    // ============================================================
    // 添加内置 ELF
    // ============================================================

    private void addBuiltinScript(
            String assetName
    ) {

        try {

            File destFile =
                    new File(
                            getFilesDir(),
                            assetName
                    );


            /*
             * 如果不存在，或者文件大小为 0，
             * 从 APK assets 重新复制。
             */
            if (!destFile.exists()
                    || destFile.length() == 0) {

                try (
                        InputStream is =
                                getAssets()
                                        .open(assetName);

                        FileOutputStream fos =
                                new FileOutputStream(
                                        destFile
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
            }


            /*
             * 设置 App 私有文件执行权限。
             */
            destFile.setExecutable(
                    true,
                    false
            );


            String path =
                    destFile
                            .getAbsolutePath();


            /*
             * 防止重复加入。
             */
            if (!scriptList.contains(path)) {

                scriptList.add(path);
            }


            /*
             * 保存列表。
             */
            saveScripts();

        } catch (Exception e) {

            appendText(
                    "内置文件加载失败："
                            + assetName
                            + "\n"
            );
        }
    }


    // ============================================================
    // 向 ELF stdin 发送输入
    // ============================================================

    private void sendInputToElf(
            String input
    ) {

        try {

            BufferedWriter currentWriter =
                    writer;

            Process currentProcess =
                    process;


            if (currentWriter == null
                    || currentProcess == null
                    || !elfRunning) {

                appendText(
                        "\n[输入通道尚未建立]\n"
                );

                return;
            }


            /*
             * 输入直接进入 ELF stdin。
             */
            currentWriter.write(
                    input
            );

            currentWriter.newLine();

            currentWriter.flush();


            etInput.post(() ->
                    etInput.setText("")
            );


        } catch (Exception e) {

            appendText(
                    "\n[输入失败] "
                            + e.getMessage()
                            + "\n"
            );
        }
    }


    // ============================================================
    // 直接执行 root 命令
    // ============================================================

    private void executeCommand(
            String cmd
    ) {

        etInput.setText("");


        new Thread(() -> {

            try {

                String finalCmd =

                        "export PATH="
                                + "/data/local/tmp"
                                + ":/system/bin"
                                + ":/system/xbin"
                                + ":/vendor/bin"
                                + ":$PATH; "
                                + cmd;


                ProcessBuilder pb =
                        new ProcessBuilder(
                                findSu(),
                                "-c",
                                finalCmd
                        );


                pb.redirectErrorStream(
                        true
                );


                Process p =
                        pb.start();


                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        p.getInputStream()
                                )
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


                    String rawOutput =
                            new String(
                                    buffer,
                                    0,
                                    count
                            );


                    String cleanOutput =
                            cleanElfOutput(
                                    rawOutput
                            );


                    if (cleanOutput.length() > 0) {

                        runOnUiThread(() ->
                                appendText(
                                        cleanOutput
                                )
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


        for (String path :
                suPaths) {

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


            while ((line =
                    reader.readLine())
                    != null) {

                output.append(
                        line
                );
            }


            int exitCode =
                    p.waitFor();


            return exitCode == 0
                    && output
                    .toString()
                    .contains(
                            "uid=0"
                    );


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

                    .setTitle(
                            "需要 root 权限"
                    )

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


    // ============================================================
    // Shell 参数安全引用
    // ============================================================

    private String shellQuote(
            String value
    ) {

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


            runElfReal(
                    scriptPath
            );

        }).start();
    }


    // ============================================================
    // 真正启动 ELF
    // ============================================================

    private void runElfReal(
            String scriptPath
    ) {

        /*
         * 停止旧 ELF。
         */
        stopCurrentElf();


        try {

            File elf =
                    new File(
                            scriptPath
                    );


            if (!elf.exists()
                    || !elf.isFile()) {

                appendText(
                        "ELF 文件不存在\n"
                );

                return;
            }


            // ====================================================
            // BusyBox
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
            // ELF 目录
            // ====================================================

            String elfDir =
                    elf.getParent();


            if (elfDir == null) {

                elfDir =
                        getFilesDir()
                                .getAbsolutePath();
            }


            // ====================================================
            // 环境
            // ====================================================

            String env =

                    "export PATH="

                            + shellQuote(
                                    busyboxFile
                                            .getParent()
                                            + ":/system/bin"
                                            + ":/system/xbin"
                                            + ":/vendor/bin"
                            )

                            + ":$PATH; "

                            + "export HOME="
                            + "/data/local/tmp; "

                            + "export TMPDIR="
                            + "/data/local/tmp; "

                            + "export LD_LIBRARY_PATH="
                            + "/system/lib64"
                            + ":/vendor/lib64"
                            + ":$LD_LIBRARY_PATH; "

                            + "cd "
                            + shellQuote(
                                    elfDir
                            )
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
            // BusyBox script + PTY
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
            // root process
            // ====================================================

            ProcessBuilder pb =
                    new ProcessBuilder(
                            suCmd,
                            "-c",
                            command
                    );


            /*
             * stdout / stderr 分开。
             */
            pb.redirectErrorStream(
                    false
            );


            try {

                pb.directory(
                        new File(
                                elfDir
                        )
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
                                            .getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );


            /*
             * stdin 成功建立。
             */
            elfRunning = true;


            // ====================================================
            // stdout
            // ====================================================

            Thread stdoutThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getInputStream(),
                                            StandardCharsets.UTF_8
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
                                            appendText(
                                                    clean
                                            )
                                    );
                                }
                            }


                        } catch (Exception ignored) {
                        }

                    });


            stdoutThread.setName(
                    "ELF-stdout"
            );


            // ====================================================
            // stderr
            // ====================================================

            Thread stderrThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getErrorStream(),
                                            StandardCharsets.UTF_8
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
                                            appendText(
                                                    clean
                                            )
                                    );
                                }
                            }


                        } catch (Exception ignored) {
                        }

                    });


            stderrThread.setName(
                    "ELF-stderr"
            );


            stdoutThread.start();

            stderrThread.start();


            // ====================================================
            // 等待结束
            // ====================================================

            new Thread(() -> {

                try {

                    currentProcess.waitFor();

                    stdoutThread.join(
                            1000
                    );

                    stderrThread.join(
                            1000
                    );

                } catch (Exception ignored) {

                } finally {

                    /*
                     * 只有当前进程还是这个 ELF
                     * 才清理全局状态。
                     */
                    if (process ==
                            currentProcess) {

                        writer = null;

                        process = null;

                        elfRunning =
                                false;
                    }
                }

            }).start();


        } catch (Exception e) {

            writer = null;

            process = null;

            elfRunning =
                    false;


            appendText(
                    "启动 ELF 失败\n"
            );
        }
    }


    // ============================================================
    // 停止当前 ELF
    // ============================================================

    private void stopCurrentElf() {

        try {

            BufferedWriter currentWriter =
                    writer;

            if (currentWriter != null) {

                currentWriter.close();
            }

        } catch (Exception ignored) {
        }


        writer = null;


        try {

            Process currentProcess =
                    process;

            if (currentProcess != null) {

                currentProcess.destroy();
            }

        } catch (Exception ignored) {
        }


        process = null;

        elfRunning =
                false;
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
                            getAssets()
                                    .open(
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
                    || tempFile.length()
                    < 100000) {

                return false;
            }


            // ====================================================
            // 安装 BusyBox
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


            int installExit =
                    installProcess.waitFor();


            if (installExit != 0) {

                return false;
            }


            // ====================================================
            // 检查 BusyBox
            // ====================================================

            if (!busyboxFile.exists()) {

                return false;
            }


            // ====================================================
            // 检查 script applet
            // ====================================================

            String scriptCheckCommand =
                    dst
                            + " --list";


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


            int checkExit =
                    scriptCheckProcess
                            .waitFor();


            if (checkExit != 0) {

                return false;
            }


            boolean hasScript =
                    false;


            for (String applet :
                    appletList
                            .split("\\s+")) {

                if ("script".equals(
                        applet.trim()
                )) {

                    hasScript =
                            true;

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
                            inputStream,
                            StandardCharsets.UTF_8
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


        /*
         * 删除真正 ANSI ESC。
         */
        text =
                text.replaceAll(
                        "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                        ""
                );


        /*
         * 删除普通文本形式 ANSI。
         */
        text =
                text.replaceAll(
                        "\\[(?:[0-9;?]+)m",
                        ""
                );


        /*
         * 删除指定文字。
         */
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

    private String getFileName(
            Uri uri
    ) {

        String result =
                null;


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


            tvName.setText(
                    fileName
            );


            /*
             * 运行
             */
            btnRun.setOnClickListener(
                    v ->
                            runElf(path)
            );


            /*
             * 删除
             */
            btnDelete.setOnClickListener(
                    v -> {

                        scriptList.remove(
                                position
                        );

                        adapter.notifyDataSetChanged();

                        saveScripts();
                    }
            );


            return convertView;
        }
    }


    // ============================================================
    // 添加输出
    // ============================================================

    private void appendText(
            String text
    ) {

        if (text == null
                || text.length() == 0) {

            return;
        }


        runOnUiThread(() -> {

            tvOutput.append(
                    text
            );


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

        stopCurrentElf();

        super.onDestroy();
    }
}


