package com.example.rootlauncher;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

    // 核心目录
    private static final String TARGET_DIR = "/data/local/tmp/com.example.rootlauncher/files";
    private static final String[] DEFAULT_SCRIPTS = {
            "Kairos_Driver_Loader_Release_90f76e9.sh",
            "TIME_Cloud_Loader_Release_1732727.sh"
    };

    // UI 控件
    private ListView lvScripts;
    private ScrollView scrollView;
    private TextView tvOutput;
    private EditText etInput;
    private Button btnAdd, btnSend;
    private ConstraintLayout topBar, bottomBar;

    // 数据
    private final List<String> scriptList = new ArrayList<>();
    private ScriptAdapter adapter;
    private Process currentProcess;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initEnvironment();
        initKeyboardListener();
        initListeners();
    }

    private void initViews() {
        topBar = findViewById(R.id.topBar);
        lvScripts = findViewById(R.id.lvScripts);
        scrollView = findViewById(R.id.scrollView);
        tvOutput = findViewById(R.id.tvOutput);
        bottomBar = findViewById(R.id.bottomBar);
        etInput = findViewById(R.id.etInput);
        btnAdd = findViewById(R.id.btnAdd);
        btnSend = findViewById(R.id.btnSend);

        adapter = new ScriptAdapter(this, scriptList);
        lvScripts.setAdapter(adapter);
    }

    /**
     * 初始化环境：创建目录、提取内置脚本、提取 BusyBox、初始化 DNS
     */
    private void initEnvironment() {
        new Thread(() -> {
            // 1. 创建 TARGET_DIR 目录
            executeSuCommand("mkdir -p " + TARGET_DIR);

            // 2. 提取 assets 中的 busybox 和内置脚本到 TARGET_DIR
            extractAssetFile("busybox", TARGET_DIR + "/busybox");
            for (String script : DEFAULT_SCRIPTS) {
                extractAssetFile(script, TARGET_DIR + "/" + script);
            }

            // 3. 赋予执行权限
            executeSuCommand("chmod 755 " + TARGET_DIR + "/*");

            // ★★★ 4. 核心修复：生成 resolv.conf 并设置环境变量，解决 nc: bad address ★★★
            String resolvPath = TARGET_DIR + "/resolv.conf";
            String dnsContent = "nameserver 114.114.114.114\\nnameserver 8.8.8.8\\n";
            executeSuCommand("echo -e \"" + dnsContent + "\" > " + resolvPath + " && chmod 644 " + resolvPath);

            mainHandler.post(() -> {
                appendOutput("环境初始化完成，BusyBox DNS 修复已注入。\n", "#00FF00");
                refreshScriptList();
            });
        }).start();
    }

    /**
     * 从 assets 提取文件到指定目录
     */
    private void extractAssetFile(String assetName, String destPath) {
        try {
            InputStream is = getAssets().open(assetName);
            File destFile = new File(destPath);
            if (!destFile.exists()) {
                OutputStream os = new FileOutputStream(destFile);
                byte[] buffer = new byte[4096];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
                os.close();
            }
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 刷新脚本列表（扫描 TARGET_DIR 下的 .sh 文件，以及用户添加的脚本）
     */
    private void refreshScriptList() {
        // 为了简化，这里先清空并重新加载默认脚本 + 用户手动添加的脚本
        // 实际应用中，你可能需要读取 SharedPreferences 存储的额外脚本列表
        scriptList.clear();
        for (String script : DEFAULT_SCRIPTS) {
            scriptList.add(script);
        }
        // 这里可以加上你从 SharedPreferences 读取的用户添加的脚本
        // 例如：scriptList.addAll(loadUserScripts());

        adapter.notifyDataSetChanged();
    }

    /**
     * 键盘弹起/收起时调整列表高度
     */
    private void initKeyboardListener() {
        scrollView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = scrollView.getRootView().getHeight() - scrollView.getHeight();
            if (heightDiff > 500) { // 键盘弹起
                updateListHeight(0.15f);
            } else { // 键盘收起
                updateListHeight(0.55f);
            }
        });
    }

    private void updateListHeight(float percent) {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) lvScripts.getLayoutParams();
        params.matchConstraintPercentHeight = percent;
        lvScripts.setLayoutParams(params);
    }

    private void initListeners() {
        // 添加脚本按钮（简单实现：弹 Toast 提示去 MT 管理器添加，或自己实现文件选择器）
        btnAdd.setOnClickListener(v -> {
            Toast.makeText(this, "请将脚本放入 " + TARGET_DIR + " 目录下", Toast.LENGTH_LONG).show();
        });

        // 发送命令
        btnSend.setOnClickListener(v -> {
            String cmd = etInput.getText().toString().trim();
            if (!TextUtils.isEmpty(cmd)) {
                etInput.setText("");
                executeCommand(cmd);
            }
        });
    }

    /**
     * 执行普通终端命令（当没有脚本运行时）
     */
    private void executeCommand(String cmd) {
        appendOutput("$ " + cmd + "\n", "#00FFFF");
        new Thread(() -> {
            // 注入环境变量，特别是 RESOLV_CONF 和 PATH
            String fullCmd = "export PATH=" + TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +
                             "export TMPDIR=" + TARGET_DIR + "; " +
                             "export RESOLV_CONF=" + TARGET_DIR + "/resolv.conf; " +
                             "cd " + TARGET_DIR + "; " + cmd;
            executeSuCommandStream(fullCmd);
        }).start();
    }

    /**
     * 执行内置 ELF 脚本（使用 busybox script 运行在 PTY 中）
     */
    private void runElfReal(String scriptPath) {
        String scriptName = new File(scriptPath).getName();
        appendOutput("\n--- 开始运行: " + scriptName + " ---\n", "#00FF00");
        
        new Thread(() -> {
            try {
                // 构造环境变量：PATH, TMPDIR, RESOLV_CONF
                String envCmd = "export PATH=" + TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +
                                "export TMPDIR=" + TARGET_DIR + "; " +
                                "export RESOLV_CONF=" + TARGET_DIR + "/resolv.conf; " +
                                "cd " + TARGET_DIR + "; ";

                // 使用 busybox script -q -c 'exec 脚本路径' /dev/null
                String command = envCmd + TARGET_DIR + "/busybox script -q -c 'exec " + scriptPath + "' /dev/null";

                ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
                pb.redirectErrorStream(true);
                
                // 显式向进程注入环境变量，双重保险
                pb.environment().put("RESOLV_CONF", TARGET_DIR + "/resolv.conf");
                pb.environment().put("PATH", TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:" + System.getenv("PATH"));
                pb.environment().put("TMPDIR", TARGET_DIR);

                currentProcess = pb.start();

                // 读取输出
                BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String cleaned = cleanElfOutput(line);
                    if (!TextUtils.isEmpty(cleaned)) {
                        appendOutput(cleaned + "\n", "#00FF00");
                    }
                }

                int exitCode = currentProcess.waitFor();
                appendOutput("\n--- 脚本执行结束，退出码: " + exitCode + " ---\n", "#FFCC00");
                currentProcess = null;

            } catch (Exception e) {
                e.printStackTrace();
                appendOutput("执行出错: " + e.getMessage() + "\n", "#FF0000");
            }
        }).start();
    }

    /**
     * 辅助方法：执行 su 命令（同步，不返回输出流）
     */
    private void executeSuCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 辅助方法：执行 su 命令并实时输出到 TextView
     */
    private void executeSuCommandStream(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String finalLine = line;
                mainHandler.post(() -> appendOutput(finalLine + "\n", "#00FFFF"));
            }
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            mainHandler.post(() -> appendOutput("错误: " + e.getMessage() + "\n", "#FF0000"));
        }
    }

    /**
     * 清理 ELF 输出中的 ANSI 颜色码和无关字样
     */
    private String cleanElfOutput(String text) {
        // 去除 ANSI 转义码
        text = text.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        text = text.replaceAll("\\[(?:[0-9;?]+)m", "");
        // 去除特定无关字样（根据你的档案需求）
        text = text.replace("公益倒卖死全家", "");
        return text;
    }

    /**
     * 追加彩色文本到输出窗口
     */
    private void appendOutput(String text, String colorHex) {
        mainHandler.post(() -> {
            /*
            // 如果以后要做彩色 Spannable，可以用这个逻辑（需要引入 SpannableString）
            SpannableString spannable = new SpannableString(text);
            spannable.setSpan(new ForegroundColorSpan(Color.parseColor(colorHex)), 0, text.length(), 0);
            tvOutput.append(spannable);
            */
            // 目前简化处理，直接追加文本（因为 XML 里设了绿色 textColor）
            tvOutput.append(text);
            // 自动滚动到底部
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ====================== 列表适配器 ======================
    private class ScriptAdapter extends ArrayAdapter<String> {
        private final Context context;
        private final List<String> items;

        public ScriptAdapter(Context context, List<String> items) {
            super(context, R.layout.item_script, items);
            this.context = context;
            this.items = items;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_script, parent, false);
            }

            TextView tvName = convertView.findViewById(R.id.tvScriptName);
            Button btnDelete = convertView.findViewById(R.id.btnDelete);
            Button btnRun = convertView.findViewById(R.id.btnRun);

            String scriptName = items.get(position);
            tvName.setText(scriptName);

            // 删除按钮：物理删除
            btnDelete.setOnClickListener(v -> {
                new Thread(() -> {
                    executeSuCommand("rm -rf " + TARGET_DIR + "/" + scriptName);
                    mainHandler.post(() -> {
                        items.remove(position);
                        notifyDataSetChanged();
                        appendOutput("已删除: " + scriptName + "\n", "#FFCC00");
                    });
                }).start();
            });

            // 运行按钮
            btnRun.setOnClickListener(v -> {
                runElfReal(TARGET_DIR + "/" + scriptName);
            });

            return convertView;
        }
    }
}
