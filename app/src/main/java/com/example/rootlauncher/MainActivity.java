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

private final ArrayList<String> scriptList =
        new ArrayList<>();

private ScriptAdapter adapter;

private volatile Process process;
private volatile BufferedWriter writer;
private volatile boolean elfRunning = false;

private String pendingScriptPath = null;

private android.content.SharedPreferences prefs;

/*
 * APK 自带的 BusyBox。
 *
 * assets/busybox
 *      ↓
 * /data/local/tmp/root_launcher_busybox
 */
private File busyboxFile;

private boolean keyboardVisible = false;

private static final int SCRIPT_LIST_KEYBOARD_DP = 120;

private static final String BUILTIN_KAIROS =
        "Kairos_Driver_Loader_Release_90f76e9.sh";

private static final String BUILTIN_TIME =
        "TIME_Cloud_Loader_Release_1732727.sh";

private static final String BUSYBOX_ASSET =
        "busybox";

private static final String BUSYBOX_INSTALL_NAME =
        "root_launcher_busybox";

// ============================================================
// 文件选择器
// ============================================================

private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
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

                            appendText(
                                    "[添加失败] 无法读取文件\n"
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

                        while ((len = is.read(buffer)) > 0) {

                            fos.write(
                                    buffer,
                                    0,
                                    len
                            );
                        }

                        is.close();
                        fos.close();

                        destFile.setExecutable(
                                true,
                                false
                        );

                        String path =
                                destFile.getAbsolutePath();

                        if (!scriptList.contains(path)) {

                            scriptList.add(path);
                        }

                        if (adapter != null) {

                            adapter.notifyDataSetChanged();
                        }

                        saveScripts();

                        appendText(
                                "[+] 已添加："
                                        + displayName
                                        + "\n"
                        );

                    } catch (Exception e) {

                        appendText(
                                "[添加文件失败] "
                                        + e.getMessage()
                                        + "\n"
                        );
                    }
                }
        );

// ============================================================
// onCreate
// ============================================================

@Override
protected void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);

    getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    );

    setContentView(
            R.layout.activity_main
    );

    tvOutput =
            findViewById(R.id.tvOutput);

    etInput =
            findViewById(R.id.etInput);

    scrollView =
            findViewById(R.id.scrollView);

    lvScripts =
            findViewById(R.id.lvScripts);

    Button btnAdd =
            findViewById(R.id.btnAdd);

    Button btnSend =
            findViewById(R.id.btnSend);

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
    // 内置 ELF
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
    // 发送
    // ========================================================

    btnSend.setOnClickListener(v -> {

        String input =
                etInput
                        .getText()
                        .toString();

        if (input.trim().isEmpty()) {
            return;
        }

        if (elfRunning
                && process != null
                && writer != null) {

            sendInputToElf(input);

        } else {

            executeCommand(input);
        }
    });

    // ========================================================
    // Root 检查
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

                boolean nowVisible =
                        keyboardHeight
                                > rootHeight * 0.15f;

                if (nowVisible
                        == keyboardVisible) {

                    return;
                }

                keyboardVisible =
                        nowVisible;

                setScriptListKeyboardMode(
                        keyboardVisible
                );
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

        params.height =
                dpToPx(
                        SCRIPT_LIST_KEYBOARD_DP
                );

        params.matchConstraintPercentHeight =
                -1f;

    } else {

        params.height = 0;

        params.matchConstraintPercentHeight =
                0.55f;
    }

    lvScripts.setLayoutParams(
            params
    );

    lvScripts.requestLayout();

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
// dp -> px
// ============================================================

private int dpToPx(int dp) {

    return (int) (
            dp
                    * getResources()
                    .getDisplayMetrics()
                    .density
                    + 0.5f
    );
}

// ============================================================
// 添加内置脚本
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

        if (!destFile.exists()
                || destFile.length() == 0) {

            InputStream is =
                    getAssets()
                            .open(assetName);

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
        }

        destFile.setExecutable(
                true,
                false
        );

        String path =
                destFile.getAbsolutePath();

        if (!scriptList.contains(path)) {

            scriptList.add(path);
        }

        saveScripts();

    } catch (Exception e) {

        appendText(
                "[内置文件加载失败] "
                        + assetName
                        + "\n"
        );
    }
}

// ============================================================
// ELF 输入
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
                    "[输入通道尚未建立]\n"
            );

            return;
        }

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
                "[ELF 输入失败] "
                        + e.getMessage()
                        + "\n"
        );
    }
}

// ============================================================
// Root Shell 命令
// ============================================================

private void executeCommand(
        String cmd
) {

    if (cmd == null
            || cmd.trim().isEmpty()) {

        return;
    }

    final String command =
            cmd.trim();

    appendText(
            "$ "
                    + command
                    + "\n"
    );

    etInput.setText("");

    new Thread(() -> {

        try {

            String finalCmd =
                    "export PATH="
                            + shellQuote(
                                    "/data/local/tmp"
                                            + ":/system/bin"
                                            + ":/system/xbin"
                                            + ":/vendor/bin"
                            )
                            + ":$PATH; "
                            + command;

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
                                    p.getInputStream(),
                                    StandardCharsets.UTF_8
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

                if (!clean.isEmpty()) {

                    runOnUiThread(() ->
                            appendText(
                                    clean
                            )
                    );
                }
            }

            int exitCode =
                    p.waitFor();

            final int code =
                    exitCode;

            runOnUiThread(() ->
                    appendText(
                            "\n[exit "
                                    + code
                                    + "]\n"
                    )
            );

        } catch (Exception e) {

            runOnUiThread(() ->
                    appendText(
                            "\n[执行失败] "
                                    + e.getMessage()
                                    + "\n"
                    )
            );
        }

    }).start();
}

// ============================================================
// su
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
                                p.getInputStream(),
                                StandardCharsets.UTF_8
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
                .contains("uid=0");

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
                        "需要 Root 权限"
                )
                .setMessage(
                        "本软件需要 Root 权限才能执行命令和 ELF。\n\n"
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
// Shell Quote
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

    stopCurrentElf();

    try {

        File elf =
                new File(
                        scriptPath
                );

        if (!elf.exists()
                || !elf.isFile()) {

            appendText(
                    "[ELF] 文件不存在\n"
            );

            return;
        }

        // ====================================================
        // APK 自带 BusyBox
        // ====================================================

        if (!extractAndPrepareBusybox()) {

            appendText(
                    "[ELF] APK 内置 BusyBox 初始化失败\n"
            );

            return;
        }

        String suCmd =
                findSu();

        String elfDir =
                elf.getParent();

        if (elfDir == null) {

            elfDir =
                    getFilesDir()
                            .getAbsolutePath();
        }

        /*
         * 设置执行环境。
         */
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
                        + "export HOME=/data/local/tmp; "
                        + "export TMPDIR=/data/local/tmp; "
                        + "export LD_LIBRARY_PATH="
                        + "/system/lib64"
                        + ":/vendor/lib64"
                        + ":$LD_LIBRARY_PATH; "
                        + "cd "
                        + shellQuote(
                                elfDir
                        )
                        + "; ";

        /*
         * ELF 本体。
         */
        String elfCommand =
                "exec "
                        + shellQuote(
                                elf.getAbsolutePath()
                        );

        /*
         * 关键：
         *
         * 不执行：
         *
         * root_launcher_busybox
         *
         * 而是明确执行：
         *
         * root_launcher_busybox script
         *
         * 这样 BusyBox 会进入 script applet。
         */
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

        ProcessBuilder pb =
                new ProcessBuilder(
                        suCmd,
                        "-c",
                        command
                );

        pb.redirectErrorStream(false);

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

        writer =
                new BufferedWriter(
                        new OutputStreamWriter(
                                currentProcess
                                        .getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                );

        elfRunning =
                true;

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

                            if (!clean.isEmpty()) {

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

                            if (!clean.isEmpty()) {

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
        // 等待 ELF 结束
        // ====================================================

        new Thread(() -> {

            try {

                int exitCode =
                        currentProcess.waitFor();

                stdoutThread.join(
                        1000
                );

                stderrThread.join(
                        1000
                );

                final int code =
                        exitCode;

                runOnUiThread(() ->
                        appendText(
                                "\n[ELF exit "
                                        + code
                                        + "]\n"
                        )
                );

            } catch (Exception ignored) {

            } finally {

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
                "[ELF 启动失败] "
                        + e.getMessage()
                        + "\n"
        );
    }
}

// ============================================================
// 停止 ELF
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

            /*
             * Android 某些情况下 destroy()
             * 不能立即结束。
             */
            if (currentProcess.isAlive()) {

                currentProcess.destroyForcibly();
            }
        }

    } catch (Exception ignored) {
    }

    process = null;

    elfRunning =
            false;
}

// ============================================================
// APK 内置 BusyBox
// ============================================================

private boolean extractAndPrepareBusybox() {

    File tempFile =
            new File(
                    getFilesDir(),
                    "busybox_temp"
            );

    try {

        // ====================================================
        // 1. 打开 APK assets/busybox
        // ====================================================

        InputStream is =
                getAssets()
                        .open(
                                BUSYBOX_ASSET
                        );

        FileOutputStream fos =
                new FileOutputStream(
                        tempFile
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

        if (!tempFile.exists()
                || tempFile.length() < 100000) {

            appendText(
                    "[BusyBox] assets/busybox 文件异常\n"
            );

            return false;
        }

        // ====================================================
        // 2. 设置目标路径
        // ====================================================

        busyboxFile =
                new File(
                        "/data/local/tmp/"
                                + BUSYBOX_INSTALL_NAME
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

        // ====================================================
        // 3. 通过 Root 安装 BusyBox
        // ====================================================

        String installCommand =
                "rm -f "
                        + dst
                        + "; "
                        + "cat "
                        + src
                        + " > "
                        + dst
                        + "; "
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

        String installOutput =
                readAll(
                        installProcess
                                .getInputStream()
                );

        int installExit =
                installProcess.waitFor();

        if (installExit != 0) {

            appendText(
                    "[BusyBox] 安装失败\n"
                            + installOutput
            );

            return false;
        }

        // ====================================================
        // 4. 检查文件
        // ====================================================

        Process fileCheck =
                new ProcessBuilder(
                        findSu(),
                        "-c",
                        "ls -l "
                                + dst
                )
                        .redirectErrorStream(true)
                        .start();

        String fileInfo =
                readAll(
                        fileCheck
                                .getInputStream()
                );

        fileCheck.waitFor();

        if (!busyboxFile.exists()) {

            appendText(
                    "[BusyBox] 安装文件不存在\n"
                            + fileInfo
            );

            return false;
        }

        // ====================================================
        // 5. 检查 BusyBox 是否能运行
        // ====================================================

        Process versionProcess =
                new ProcessBuilder(
                        findSu(),
                        "-c",
                        dst + " --help"
                )
                        .redirectErrorStream(true)
                        .start();

        String versionOutput =
                readAll(
                        versionProcess
                                .getInputStream()
                );

        int versionExit =
                versionProcess.waitFor();

        if (versionExit != 0) {

            appendText(
                    "[BusyBox] 无法执行\n"
                            + versionOutput
            );

            return false;
        }

        // ====================================================
        // 6. 检查 script applet
        // ====================================================

        Process listProcess =
                new ProcessBuilder(
                        findSu(),
                        "-c",
                        dst + " --list"
                )
                        .redirectErrorStream(true)
                        .start();

        String appletList =
                readAll(
                        listProcess
                                .getInputStream()
                );

        int listExit =
                listProcess.waitFor();

        if (listExit != 0) {

            appendText(
                    "[BusyBox] 无法读取 applet\n"
                            + appletList
            );

            return false;
        }

        boolean hasScript =
                hasBusyboxApplet(
                        appletList,
                        "script"
                );

        if (!hasScript) {

            appendText(
                    "[BusyBox] 不包含 script applet\n"
            );

            return false;
        }

        // ====================================================
        // 7. 最终成功
        // ====================================================

        appendText(
                "[+] APK 内置 BusyBox 已准备\n"
        );

        return true;

    } catch (Exception e) {

        appendText(
                "[BusyBox] 初始化异常："
                        + e.getMessage()
                        + "\n"
        );

        return false;

    } finally {

        /*
         * 删除 App 私有目录里的临时复制。
         *
         * /data/local/tmp/root_launcher_busybox
         * 才是实际运行版本。
         */
        try {

            if (tempFile.exists()) {

                tempFile.delete();
            }

        } catch (Exception ignored) {
        }
    }
}

// ============================================================
// BusyBox applet 检查
// ============================================================

private boolean hasBusyboxApplet(
        String appletList,
        String wanted
) {

    if (appletList == null
            || wanted == null) {

        return false;
    }

    String[] applets =
            appletList.split(
                    "\\s+"
            );

    for (String applet :
            applets) {

        if (wanted.equals(
                applet.trim()
        )) {

            return true;
        }
    }

    return false;
}

// ============================================================
// 读取全部输出
// ============================================================

private String readAll(
        InputStream inputStream
) {

    StringBuilder result =
            new StringBuilder();

    if (inputStream == null) {

        return "";
    }

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

    text =
            text.replaceAll(
                    "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                    ""
            );

    text =
            text.replaceAll(
                    "\\[(?:[0-9;?]+)m",
                    ""
            );

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
// 保存脚本
// ============================================================

private void saveScripts() {

    if (prefs == null) {
        return;
    }

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
// Script Adapter
// ============================================================

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

        if (tvName != null) {

            tvName.setText(
                    fileName
            );
        }

        if (btnRun != null) {

            btnRun.setOnClickListener(
                    v -> runElf(path)
            );
        }

        if (btnDelete != null) {

            btnDelete.setOnClickListener(
                    v -> {

                        if (position >= 0
                                && position
                                < scriptList.size()) {

                            scriptList.remove(
                                    position
                            );

                            adapter.notifyDataSetChanged();

                            saveScripts();
                        }
                    }
            );
        }

        return convertView;
    }
}

// ============================================================
// 输出
// ============================================================

private void appendText(
        String text
) {

    if (text == null
            || text.length() == 0) {

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

            scrollView.post(() ->
                    scrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        }
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

