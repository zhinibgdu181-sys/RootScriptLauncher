package com.example.rootlauncher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_DIR =
            "/data/local/tmp/com.example.rootlauncher/files";

    private static final String[] DEFAULT_SCRIPTS = {
            "Kairos_Driver_Loader_Release_90f76e9.sh",
            "TIME_Cloud_Loader_Release_1732727.sh"
    };

    /*
     * 如果还没有正式签名，就保持这个占位值。
     * 占位状态下允许运行。
     */
    private static final String OFFICIAL_SIGNATURE =
            "你的正式签名Base64字符串==";

    private ListView lvScripts;
    private ScrollView scrollView;
    private TextView tvOutput;
    private EditText etInput;
    private Button btnAdd;
    private Button btnSend;

    private final List<String> scriptList =
            new ArrayList<>();

    private ScriptAdapter adapter;

    private volatile Process currentProcess;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private boolean isInitialized = false;

    // ============================================================
    // Activity
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 签名检查
        if (!checkSignature()) {
            Toast.makeText(
                    this,
                    "签名校验失败，请使用官方版本！",
                    Toast.LENGTH_LONG
            ).show();

            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Root 检查
        if (!checkRootPermission()) {
            showRootDialog();
            return;
        }

        initViews();

        initEnvironment();

        initListeners();

        isInitialized = true;

        updateListHeight(0.45f);
    }

    // ============================================================
    // 键盘监听
    // ============================================================

    private void initKeyboardListener() {

        final View rootView =
                findViewById(android.R.id.content);

        rootView.getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> {

                    if (!isInitialized) {
                        return;
                    }

                    Rect r = new Rect();

                    rootView
                            .getWindowVisibleDisplayFrame(r);

                    int screenHeight =
                            rootView.getRootView().getHeight();

                    int keypadHeight =
                            screenHeight - r.bottom;

                    if (keypadHeight >
                            screenHeight * 0.15f) {

                        // 键盘弹出
                        updateListHeight(0.15f);

                    } else {

                        // 键盘收起
                        updateListHeight(0.45f);
                    }
                });
    }

    private void updateListHeight(float percent) {

        if (lvScripts == null) {
            return;
        }

        ViewGroup.LayoutParams rawParams =
                lvScripts.getLayoutParams();

        if (!(rawParams instanceof
                ConstraintLayout.LayoutParams)) {
            return;
        }

        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) rawParams;

        params.height = 0;

        params.matchConstraintPercentHeight =
                percent;

        params.matchConstraintDefaultHeight =
                ConstraintLayout.LayoutParams
                        .MATCH_CONSTRAINT_PERCENT;

        lvScripts.setLayoutParams(params);

        lvScripts.requestLayout();
    }

    // ============================================================
    // Root 检查
    // ============================================================

    private boolean checkRootPermission() {

        Process process = null;

        try {

            process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            "id"
                    )
                            .redirectErrorStream(true)
                            .start();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line =
                    reader.readLine()) != null) {

                output.append(line)
                        .append('\n');
            }

            int exitCode =
                    process.waitFor();

            return exitCode == 0 &&
                    output.toString()
                            .contains("uid=0");

        } catch (Exception e) {

            return false;

        } finally {

            if (process != null) {
                process.destroy();
            }
        }
    }

    private void showRootDialog() {

        new AlertDialog.Builder(this)

                .setTitle("需要 Root 权限")

                .setMessage(
                        "本应用需要 Root 权限才能运行 ELF。\n\n" +
                        "请在 KernelSU / Magisk 中允许本应用，" +
                        "然后重新检测。"
                )

                .setPositiveButton(
                        "重新检测",
                        (dialog, which) -> {

                            new Thread(() -> {

                                if (checkRootPermission()) {

                                    mainHandler.post(() -> {

                                        initViews();

                                        initEnvironment();

                                        initListeners();

                                        isInitialized = true;

                                        updateListHeight(
                                                0.45f
                                        );
                                    });

                                } else {

                                    mainHandler.post(() -> {

                                        Toast.makeText(
                                                MainActivity.this,
                                                "仍未获取 Root 权限！",
                                                Toast.LENGTH_SHORT
                                        ).show();

                                        showRootDialog();
                                    });
                                }

                            }).start();
                        }
                )

                .setNegativeButton(
                        "退出应用",
                        (dialog, which) -> finish()
                )

                .setCancelable(false)

                .show();
    }

    // ============================================================
    // 签名检查
    // ============================================================

    private boolean checkSignature() {

        try {

            PackageManager pm =
                    getPackageManager();

            PackageInfo packageInfo;

            if (android.os.Build.VERSION.SDK_INT >= 28) {

                packageInfo =
                        pm.getPackageInfo(
                                getPackageName(),
                                PackageManager
                                        .GET_SIGNING_CERTIFICATES
                        );

                if (packageInfo.signingInfo ==
                        null) {
                    return false;
                }

                android.content.pm.Signature[] signatures =
                        packageInfo.signingInfo
                                .getApkContentsSigners();

                if (signatures == null ||
                        signatures.length == 0) {
                    return false;
                }

                byte[] data =
                        signatures[0].toByteArray();

                String currentSig =
                        Base64.encodeToString(
                                data,
                                Base64.DEFAULT
                        );

                // 尚未配置正式签名
                if (OFFICIAL_SIGNATURE.equals(
                        "你的正式签名Base64字符串==")) {

                    return true;
                }

                return currentSig.trim().equals(
                        OFFICIAL_SIGNATURE.trim()
                );

            } else {

                packageInfo =
                        pm.getPackageInfo(
                                getPackageName(),
                                PackageManager.GET_SIGNATURES
                        );

                if (packageInfo.signatures ==
                        null ||
                        packageInfo.signatures.length == 0) {
                    return false;
                }

                String currentSig =
                        Base64.encodeToString(
                                packageInfo.signatures[0]
                                        .toByteArray(),
                                Base64.DEFAULT
                        );

                if (OFFICIAL_SIGNATURE.equals(
                        "你的正式签名Base64字符串==")) {

                    return true;
                }

                return currentSig.trim().equals(
                        OFFICIAL_SIGNATURE.trim()
                );
            }

        } catch (Exception e) {

            e.printStackTrace();

            return false;
        }
    }

    // ============================================================
    // View 初始化
    // ============================================================

    private void initViews() {

        lvScripts =
                findViewById(R.id.lvScripts);

        scrollView =
                findViewById(R.id.scrollView);

        tvOutput =
                findViewById(R.id.tvOutput);

        etInput =
                findViewById(R.id.etInput);

        btnAdd =
                findViewById(R.id.btnAdd);

        btnSend =
                findViewById(R.id.btnSend);

        adapter =
                new ScriptAdapter(
                        this,
                        scriptList
                );

        lvScripts.setAdapter(adapter);

        initKeyboardListener();
    }

    // ============================================================
    // 环境初始化
    // ============================================================

    private void initEnvironment() {

        new Thread(() -> {

            try {

                /*
                 * 创建 Root 目录。
                 */
                boolean mkdirOk =
                        executeSuCommand(
                                "mkdir -p " +
                                shellQuote(TARGET_DIR)
                        );

                if (!mkdirOk) {

                    showError(
                            "无法创建 Root 目录：\n" +
                            TARGET_DIR
                    );

                    return;
                }

                /*
                 * BusyBox：
                 *
                 * assets
                 *   ↓
                 * App 私有目录
                 *   ↓
                 * su
                 *   ↓
                 * /data/local/tmp
                 */
                boolean busyboxOk =
                        installAssetAsRoot(
                                "busybox",
                                TARGET_DIR +
                                        "/busybox"
                        );

                if (!busyboxOk) {

                    showError(
                            "BusyBox 安装失败。\n\n" +
                            "目标路径：\n" +
                            TARGET_DIR +
                            "/busybox"
                    );

                    return;
                }

                /*
                 * 安装默认 ELF。
                 */
                for (String script :
                        DEFAULT_SCRIPTS) {

                    boolean ok =
                            installAssetAsRoot(
                                    script,
                                    TARGET_DIR +
                                            "/" +
                                            script
                            );

                    if (!ok) {

                        showError(
                                "脚本安装失败：\n" +
                                script +
                                "\n\n" +
                                "请确认该文件确实位于 " +
                                "app/src/main/assets/"
                        );

                        return;
                    }
                }

                /*
                 * 设置权限。
                 */
                executeSuCommand(
                        "chmod 755 " +
                        shellQuote(
                                TARGET_DIR +
                                "/busybox"
                        )
                );

                executeSuCommand(
                        "chmod 755 " +
                        shellQuote(
                                TARGET_DIR
                        ) +
                        "/*.sh"
                );

                /*
                 * 创建 DNS 文件。
                 */
                String resolvPath =
                        TARGET_DIR +
                                "/resolv.conf";

                String dnsCommand =
                        "printf '%s\\n' " +
                        "'nameserver 114.114.114.114' " +
                        "'nameserver 8.8.8.8' > " +
                        shellQuote(resolvPath) +
                        " && chmod 644 " +
                        shellQuote(resolvPath);

                executeSuCommand(dnsCommand);

                /*
                 * 最终验证。
                 */
                String verifyCommand =
                        "echo '=== ENV CHECK ==='; " +

                        "ls -l " +
                        shellQuote(TARGET_DIR) +
                        "; " +

                        shellQuote(
                                TARGET_DIR +
                                "/busybox"
                        ) +
                        " --list | " +
                        "grep '^script$'";

                String verifyOutput =
                        executeSuCommandGetOutput(
                                verifyCommand
                        );

                mainHandler.post(() -> {

                    appendOutput(
                            "\n环境初始化完成。\n",
                            "#00FF00"
                    );

                    appendOutput(
                            verifyOutput,
                            "#00FF00"
                    );

                    refreshScriptList();
                });

            } catch (Exception e) {

                showError(
                        "环境初始化异常：\n" +
                        e.getMessage()
                );
            }

        }).start();
    }

    // ============================================================
    // Asset 安装
    // ============================================================

    private boolean installAssetAsRoot(
            String assetName,
            String destPath) {

        File tempFile =
                new File(
                        getFilesDir(),
                        "asset_" +
                        System.currentTimeMillis() +
                        "_" +
                        assetName
                );

        try {

            /*
             * Asset → App 私有目录
             */
            try (
                    InputStream is =
                            getAssets()
                                    .open(assetName);

                    OutputStream os =
                            new FileOutputStream(
                                    tempFile
                            )
            ) {

                byte[] buffer =
                        new byte[8192];

                int len;

                while ((len =
                        is.read(buffer)) != -1) {

                    os.write(
                            buffer,
                            0,
                            len
                    );
                }

                os.flush();
            }

            if (!tempFile.exists() ||
                    tempFile.length() == 0) {

                return false;
            }

            /*
             * App 私有目录
             *       ↓
             * su
             *       ↓
             * /data/local/tmp
             */
            String command =
                    "mkdir -p " +
                    shellQuote(TARGET_DIR) +
                    " && " +

                    "cat " +
                    shellQuote(
                            tempFile.getAbsolutePath()
                    ) +
                    " > " +
                    shellQuote(destPath) +
                    " && " +

                    "chmod 755 " +
                    shellQuote(destPath);

            Process process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            process.getInputStream()
                    );

            int exitCode =
                    process.waitFor();

            /*
             * 删除 App 私有临时文件。
             */
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();

            if (exitCode != 0) {

                return false;
            }

            /*
             * Root 侧验证。
             */
            String verify =
                    executeSuCommandGetOutput(
                            "test -f " +
                            shellQuote(destPath) +
                            " && " +
                            "test -x " +
                            shellQuote(destPath) +
                            " && echo OK"
                    );

            return verify.contains("OK");

        } catch (Exception e) {

            e.printStackTrace();

            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();

            return false;
        }
    }

    // ============================================================
    // Root 命令
    // ============================================================

    private boolean executeSuCommand(
            String cmd) {

        Process process = null;

        try {

            process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            cmd
                    )
                            .redirectErrorStream(true)
                            .start();

            readAll(
                    process.getInputStream()
            );

            int exitCode =
                    process.waitFor();

            return exitCode == 0;

        } catch (Exception e) {

            return false;

        } finally {

            if (process != null) {
                process.destroy();
            }
        }
    }

    private String executeSuCommandGetOutput(
            String cmd) {

        Process process = null;

        try {

            process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            cmd
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            process.getInputStream()
                    );

            process.waitFor();

            return output;

        } catch (Exception e) {

            return "命令执行失败: " +
                    e.getMessage() +
                    "\n";

        } finally {

            if (process != null) {
                process.destroy();
            }
        }
    }

    // ============================================================
    // 刷新脚本列表
    // ============================================================

    private void refreshScriptList() {

        if (adapter == null) {
            return;
        }

        scriptList.clear();

        for (String script :
                DEFAULT_SCRIPTS) {

            String path =
                    TARGET_DIR +
                            "/" +
                            script;

            String result =
                    executeSuCommandGetOutput(
                            "test -f " +
                            shellQuote(path) +
                            " && " +
                            "test -x " +
                            shellQuote(path) +
                            " && echo EXISTS"
                    );

            if (result.contains("EXISTS")) {

                scriptList.add(script);
            }
        }

        mainHandler.post(() ->
                adapter.notifyDataSetChanged()
        );
    }

    // ============================================================
    // 按钮
    // ============================================================

    private void initListeners() {

        btnAdd.setOnClickListener(v -> {

            Toast.makeText(
                    this,
                    "默认脚本通过 assets 自动部署。",
                    Toast.LENGTH_LONG
            ).show();
        });

        btnSend.setOnClickListener(v -> {

            String cmd =
                    etInput.getText()
                            .toString()
                            .trim();

            if (TextUtils.isEmpty(cmd)) {
                return;
            }

            etInput.setText("");

            /*
             * ELF 正在运行：
             * 把输入发送给 ELF。
             */
            if (currentProcess != null) {

                sendInputToProcess(cmd);

            } else {

                /*
                 * 没有 ELF 运行：
                 * 执行 Root 命令。
                 */
                executeCommand(cmd);
            }
        });
    }

    // ============================================================
    // 普通 Root 命令
    // ============================================================

    private void executeCommand(
            String cmd) {

        appendOutput(
                "$ " + cmd + "\n",
                "#00FFFF"
        );

        new Thread(() -> {

            String fullCmd =
                    "export PATH=" +
                    shellQuote(TARGET_DIR) +
                    ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +

                    "export HOME=" +
                    shellQuote(TARGET_DIR) +
                    "; " +

                    "export TMPDIR=" +
                    shellQuote(TARGET_DIR) +
                    "; " +

                    "export RESOLV_CONF=" +
                    shellQuote(
                            TARGET_DIR +
                            "/resolv.conf"
                    ) +
                    "; " +

                    "cd " +
                    shellQuote(TARGET_DIR) +
                    "; " +

                    cmd;

            executeSuCommandStream(
                    fullCmd
            );

        }).start();
    }

    // ============================================================
    // ELF 运行
    // ============================================================

    private void runElfReal(
            String scriptPath) {

        new Thread(() -> {

            try {

                /*
                 * 检查 ELF。
                 */
                if (!rootFileExists(
                        scriptPath)) {

                    showError(
                            "脚本不存在或不可执行：\n" +
                            scriptPath
                    );

                    return;
                }

                /*
                 * 检查 BusyBox。
                 */
                String busybox =
                        TARGET_DIR +
                                "/busybox";

                if (!rootFileExists(
                        busybox)) {

                    showError(
                            "BusyBox 不存在：\n" +
                            busybox
                    );

                    return;
                }

                /*
                 * 检查 script applet。
                 */
                String applet =
                        executeSuCommandGetOutput(
                                shellQuote(busybox) +
                                " --list | " +
                                "grep '^script$'"
                        );

                if (!applet.contains(
                        "script")) {

                    showError(
                            "BusyBox 没有 script applet。\n\n" +
                            "Kairos 需要 PTY 才能正常交互。"
                    );

                    return;
                }

                String scriptName =
                        new File(scriptPath)
                                .getName();

                mainHandler.post(() ->
                        appendOutput(
                                "\n--- 开始运行: " +
                                scriptName +
                                " ---\n",
                                "#00FF00"
                        )
                );

                /*
                 * 环境变量。
                 */
                String envCmd =
                        "export PATH=" +
                        shellQuote(TARGET_DIR) +
                        ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +

                        "export HOME=" +
                        shellQuote(TARGET_DIR) +
                        "; " +

                        "export TMPDIR=" +
                        shellQuote(TARGET_DIR) +
                        "; " +

                        "export RESOLV_CONF=" +
                        shellQuote(
                                TARGET_DIR +
                                "/resolv.conf"
                        ) +
                        "; " +

                        "export LD_LIBRARY_PATH=/system/lib64:/vendor/lib64:$LD_LIBRARY_PATH; " +

                        "cd " +
                        shellQuote(TARGET_DIR) +
                        "; ";

                /*
                 * 关键：
                 *
                 * BusyBox script
                 *      ↓
                 * PTY
                 *      ↓
                 * exec ELF
                 */
                String command =
                        envCmd +

                        shellQuote(busybox) +

                        " script -q -c " +

                        shellQuote(
                                "exec " +
                                shellQuote(scriptPath)
                        ) +

                        " /dev/null";

                ProcessBuilder pb =
                        new ProcessBuilder(
                                "su",
                                "-c",
                                command
                        );

                /*
                 * Java Process 环境。
                 */
                pb.environment().put(
                        "PATH",
                        TARGET_DIR +
                        ":/system/bin:/system/xbin:/vendor/bin:" +
                        System.getenv("PATH")
                );

                pb.environment().put(
                        "HOME",
                        TARGET_DIR
                );

                pb.environment().put(
                        "TMPDIR",
                        TARGET_DIR
                );

                pb.environment().put(
                        "RESOLV_CONF",
                        TARGET_DIR +
                        "/resolv.conf"
                );

                pb.environment().put(
                        "LD_LIBRARY_PATH",
                        "/system/lib64:/vendor/lib64:" +
                        System.getenv(
                                "LD_LIBRARY_PATH"
                        )
                );

                pb.redirectErrorStream(true);

                currentProcess =
                        pb.start();

                /*
                 * 不使用 readLine()。
                 *
                 * Kairos 的：
                 *
                 * 请输入选择(1或2) ->
                 *
                 * 可能没有换行。
                 */
                InputStream input =
                        currentProcess
                                .getInputStream();

                byte[] buffer =
                        new byte[2048];

                int count;

                while ((count =
                        input.read(buffer)) != -1) {

                    if (count <= 0) {
                        continue;
                    }

                    String raw =
                            new String(
                                    buffer,
                                    0,
                                    count
                            );

                    String cleaned =
                            cleanElfOutput(raw);

                    if (!TextUtils.isEmpty(
                            cleaned)) {

                        final String output =
                                cleaned;

                        mainHandler.post(() ->
                                appendOutput(
                                        output,
                                        "#00FF00"
                                )
                        );
                    }
                }

                int exitCode =
                        currentProcess.waitFor();

                currentProcess = null;

                mainHandler.post(() ->
                        appendOutput(
                                "\n--- 脚本执行结束，退出码: " +
                                exitCode +
                                " ---\n",
                                "#FFCC00"
                        )
                );

            } catch (Exception e) {

                currentProcess = null;

                mainHandler.post(() ->
                        appendOutput(
                                "执行出错: " +
                                e.getMessage() +
                                "\n",
                                "#FF0000"
                        )
                );
            }
        }).start();
    }

    // ============================================================
    // 给 ELF 输入
    // ============================================================

    private void sendInputToProcess(
            String input) {

        try {

            OutputStream output =
                    currentProcess
                            .getOutputStream();

            output.write(
                    (input + "\n")
                            .getBytes()
            );

            output.flush();

        } catch (Exception e) {

            appendOutput(
                    "输入失败: " +
                    e.getMessage() +
                    "\n",
                    "#FF0000"
            );
        }
    }

    // ============================================================
    // 文件检查
    // ============================================================

    private boolean rootFileExists(
            String path) {

        String result =
                executeSuCommandGetOutput(
                        "test -f " +
                        shellQuote(path) +
                        " && " +
                        "test -x " +
                        shellQuote(path) +
                        " && echo OK"
                );

        return result.contains("OK");
    }

    // ============================================================
    // Root 流式输出
    // ============================================================

    private void executeSuCommandStream(
            String cmd) {

        try {

            Process process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            cmd
                    )
                            .redirectErrorStream(true)
                            .start();

            InputStream input =
                    process.getInputStream();

            byte[] buffer =
                    new byte[2048];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                if (count <= 0) {
                    continue;
                }

                String output =
                        new String(
                                buffer,
                                0,
                                count
                        );

                final String finalOutput =
                        output;

                mainHandler.post(() ->
                        appendOutput(
                                finalOutput,
                                "#00FFFF"
                        )
                );
            }

            process.waitFor();

        } catch (Exception e) {

            mainHandler.post(() ->
                    appendOutput(
                            "错误: " +
                            e.getMessage() +
                            "\n",
                            "#FF0000"
                    )
            );
        }
    }

    // ============================================================
    // 清理 ANSI
    // ============================================================

    private String cleanElfOutput(
            String text) {

        if (text == null) {
            return "";
        }

        /*
         * ANSI CSI。
         */
        text = text.replaceAll(
                "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                ""
        );

        /*
         * ANSI OSC。
         */
        text = text.replaceAll(
                "\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)",
                ""
        );

        /*
         * 颜色标记。
         */
        text = text.replaceAll(
                "\\[(?:[0-9;?]+)m",
                ""
        );

        /*
         * 隐藏指定文本。
         */
        text = text.replace(
                "公益倒卖死全家",
                ""
        );

        return text;
    }

    // ============================================================
    // UI 输出
    // ============================================================

    private void appendOutput(
            String text,
            String colorHex) {

        mainHandler.post(() -> {

            if (tvOutput == null) {
                return;
            }

            tvOutput.append(text);

            if (scrollView != null) {

                scrollView.post(() ->
                        scrollView.fullScroll(
                                View.FOCUS_DOWN
                        )
                );
            }
        });
    }

    private void showError(
            String message) {

        mainHandler.post(() ->
                appendOutput(
                        "\n[错误]\n" +
                        message +
                        "\n",
                        "#FF0000"
                )
        );
    }

    // ============================================================
    // Shell 转义
    // ============================================================

    private String shellQuote(
            String value) {

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

    // ============================================================
    // 读取全部
    // ============================================================

    private String readAll(
            InputStream inputStream) {

        StringBuilder result =
                new StringBuilder();

        try {

            byte[] buffer =
                    new byte[4096];

            int len;

            while ((len =
                    inputStream.read(buffer)) != -1) {

                if (len > 0) {

                    result.append(
                            new String(
                                    buffer,
                                    0,
                                    len
                            )
                    );
                }
            }

        } catch (Exception ignored) {
        }

        return result.toString();
    }

    // ============================================================
    // Adapter
    // ============================================================

    private class ScriptAdapter
            extends ArrayAdapter<String> {

        private final Context context;
        private final List<String> items;

        public ScriptAdapter(
                Context context,
                List<String> items) {

            super(
                    context,
                    R.layout.item_script,
                    items
            );

            this.context = context;
            this.items = items;
        }

        @NonNull
        @Override
        public View getView(
                int position,
                View convertView,
                @NonNull ViewGroup parent) {

            if (convertView == null) {

                convertView =
                        LayoutInflater
                                .from(context)
                                .inflate(
                                        R.layout.item_script,
                                        parent,
                                        false
                                );
            }

            TextView tvName =
                    convertView.findViewById(
                            R.id.tvScriptName
                    );

            Button btnDelete =
                    convertView.findViewById(
                            R.id.btnDelete
                    );

            Button btnRun =
                    convertView.findViewById(
                            R.id.btnRun
                    );

            String scriptName =
                    items.get(position);

            tvName.setText(scriptName);

            /*
             * 删除。
             */
            btnDelete.setOnClickListener(v -> {

                new Thread(() -> {

                    String path =
                            TARGET_DIR +
                            "/" +
                            scriptName;

                    boolean ok =
                            executeSuCommand(
                                    "rm -f " +
                                    shellQuote(path)
                            );

                    mainHandler.post(() -> {

                        if (position >= 0 &&
                                position < items.size()) {

                            items.remove(position);

                            notifyDataSetChanged();
                        }

                        appendOutput(
                                ok
                                        ? "已删除: " +
                                          scriptName +
                                          "\n"
                                        : "删除失败: " +
                                          scriptName +
                                          "\n",
                                "#FFCC00"
                        );
                    });

                }).start();
            });

            /*
             * 运行 ELF。
             */
            btnRun.setOnClickListener(v -> {

                String path =
                        TARGET_DIR +
                        "/" +
                        scriptName;

                runElfReal(path);
            });

            return convertView;
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    protected void onDestroy() {

        super.onDestroy();

        try {

            if (currentProcess != null) {

                currentProcess.destroy();
            }

        } catch (Exception ignored) {
        }

        currentProcess = null;
    }
}
