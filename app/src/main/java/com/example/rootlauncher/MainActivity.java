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

    private static final String TARGET_DIR = "/data/local/tmp/com.example.rootlauncher/files";
    private static final String[] DEFAULT_SCRIPTS = {
            "Kairos_Driver_Loader_Release_90f76e9.sh",
            "TIME_Cloud_Loader_Release_1732727.sh"
    };

    private static final String OFFICIAL_SIGNATURE = "你的正式签名Base64字符串==";

    private ListView lvScripts;
    private ScrollView scrollView;
    private TextView tvOutput;
    private EditText etInput;
    private Button btnAdd, btnSend;

    private final List<String> scriptList = new ArrayList<>();
    private ScriptAdapter adapter;
    private Process currentProcess;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 核心标记：防止重复初始化
    private boolean isInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!checkSignature()) {
            Toast.makeText(this, "签名校验失败，请使用官方版本！", Toast.LENGTH_LONG).show();
            finish();
            System.exit(0);
            return;
        }

        setContentView(R.layout.activity_main);

        if (!checkRootPermission()) {
            showRootDialog();
            return;
        }

        initViews();
        initEnvironment();
        initListeners();
        isInitialized = true;
    }

    // ====================== 键盘监听模块 ======================
    private void initKeyboardListener() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!isInitialized) return;

            Rect r = new Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;

            // 如果键盘高度超过屏幕的 15%，说明键盘弹出了
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹起：列表高度缩到 15%，终端跟着键盘上去
                updateListHeight(0.15f);
            } else {
                // 键盘收起：列表恢复 70%，终端保持短小
                updateListHeight(0.70f);
            }
        });
    }

    private void updateListHeight(float percent) {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) lvScripts.getLayoutParams();
        // 强制设回0，确保百分比生效
        params.height = 0; 
        params.matchConstraintPercentHeight = percent;
        params.matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT;
        lvScripts.setLayoutParams(params);
        lvScripts.requestLayout(); // 强制刷新
    }
    // =====================================================

    // ====================== Root 检测与弹窗模块 ======================
    private boolean checkRootPermission() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su -c id");
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = in.readLine();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void showRootDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("未找到可用的 su 环境（IOException）。\n请确保您的设备已获取 Root 权限。")
                .setPositiveButton("重新检测", (dialog, which) -> {
                    if (checkRootPermission()) {
                        initViews();
                        initEnvironment();
                        initListeners();
                        isInitialized = true;
                    } else {
                        Toast.makeText(MainActivity.this, "仍未获取 Root 权限！", Toast.LENGTH_SHORT).show();
                        showRootDialog();
                    }
                })
                .setNegativeButton("退出应用", (dialog, which) -> {
                    finish();
                    System.exit(0);
                })
                .setCancelable(false)
                .show();
    }

    // ====================== 签名校验模块 ======================
    private boolean checkSignature() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNATURES);
            String currentSig = Base64.encodeToString(
                    packageInfo.signatures[0].toByteArray(), Base64.DEFAULT);
            if (OFFICIAL_SIGNATURE.equals("你的正式签名Base64字符串==")) {
                return true;
            }
            return currentSig.equals(OFFICIAL_SIGNATURE);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ====================== 初始化模块 ======================
    private void initViews() {
        lvScripts = findViewById(R.id.lvScripts);
        scrollView = findViewById(R.id.scrollView);
        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        btnAdd = findViewById(R.id.btnAdd);
        btnSend = findViewById(R.id.btnSend);

        adapter = new ScriptAdapter(this, scriptList);
        lvScripts.setAdapter(adapter);
        
        // 绑定键盘监听
        initKeyboardListener();
    }

    private void initEnvironment() {
        new Thread(() -> {
            executeSuCommand("mkdir -p " + TARGET_DIR);

            extractAssetFile("busybox", TARGET_DIR + "/busybox");
            for (String script : DEFAULT_SCRIPTS) {
                extractAssetFile(script, TARGET_DIR + "/" + script);
            }

            executeSuCommand("chmod 755 " + TARGET_DIR + "/*");

            String resolvPath = TARGET_DIR + "/resolv.conf";
            String dnsContent = "nameserver 114.114.114.114\\nnameserver 8.8.8.8\\n";
            executeSuCommand("echo -e \"" + dnsContent + "\" > " + resolvPath + " && chmod 644 " + resolvPath);

            mainHandler.post(() -> {
                appendOutput("\n环境初始化完成，可以开始运行脚本。\n", "#00FF00");
                refreshScriptList();
                // ★★★ 重点：这里绝对不要碰布局！不调用任何 updateListHeight ★★★
            });
        }).start();
    }

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

    // ====================== 运行与执行逻辑 ======================
    private void refreshScriptList() {
        scriptList.clear();
        for (String script : DEFAULT_SCRIPTS) {
            scriptList.add(script);
        }
        adapter.notifyDataSetChanged();
    }

    private void initListeners() {
        btnAdd.setOnClickListener(v -> Toast.makeText(this, "请将脚本放入 " + TARGET_DIR + " 目录下", Toast.LENGTH_LONG).show());

        btnSend.setOnClickListener(v -> {
            String cmd = etInput.getText().toString().trim();
            if (!TextUtils.isEmpty(cmd)) {
                etInput.setText("");
                executeCommand(cmd);
            }
        });
    }

    private void executeCommand(String cmd) {
        appendOutput("$ " + cmd + "\n", "#00FFFF");
        new Thread(() -> {
            String fullCmd = "export PATH=" + TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +
                             "export TMPDIR=" + TARGET_DIR + "; " +
                             "export RESOLV_CONF=" + TARGET_DIR + "/resolv.conf; " +
                             "cd " + TARGET_DIR + "; " + cmd;
            executeSuCommandStream(fullCmd);
        }).start();
    }

    private void runElfReal(String scriptPath) {
        String scriptName = new File(scriptPath).getName();
        appendOutput("\n--- 开始运行: " + scriptName + " ---\n", "#00FF00");
        
        new Thread(() -> {
            try {
                String envCmd = "export PATH=" + TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +
                                "export TMPDIR=" + TARGET_DIR + "; " +
                                "export RESOLV_CONF=" + TARGET_DIR + "/resolv.conf; " +
                                "cd " + TARGET_DIR + "; ";

                String command = envCmd + TARGET_DIR + "/busybox script -q -c 'exec " + scriptPath + "' /dev/null";

                ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
                pb.redirectErrorStream(true);
                pb.environment().put("RESOLV_CONF", TARGET_DIR + "/resolv.conf");
                pb.environment().put("PATH", TARGET_DIR + ":/system/bin:/system/xbin:/vendor/bin:" + System.getenv("PATH"));
                pb.environment().put("TMPDIR", TARGET_DIR);

                currentProcess = pb.start();

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

    private void executeSuCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    private String cleanElfOutput(String text) {
        text = text.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        text = text.replaceAll("\\[(?:[0-9;?]+)m", "");
        text = text.replace("公益倒卖死全家", "");
        return text;
    }

    private void appendOutput(String text, String colorHex) {
        mainHandler.post(() -> {
            tvOutput.append(text);
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

            btnRun.setOnClickListener(v -> runElfReal(TARGET_DIR + "/" + scriptName));

            return convertView;
        }
    }
}
