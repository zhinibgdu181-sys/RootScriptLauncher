package com.example.rootlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {
    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;
    private ArrayList<String> scriptList = new ArrayList<>();
    private ScriptAdapter adapter;
    private Process process;
    private BufferedWriter writer;

    private final androidx.activity.result.ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String fileName = "script_" + System.currentTimeMillis() + ".sh";
                    File destFile = new File(getFilesDir(), fileName);
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        FileOutputStream fos = new FileOutputStream(destFile);
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                        is.close(); fos.close();
                        Runtime.getRuntime().exec("chmod 755 " + destFile.getAbsolutePath()).waitFor();
                        scriptList.add(destFile.getAbsolutePath());
                        adapter.notifyDataSetChanged();
                        appendText("√ 已添加脚本: " + destFile.getName() + "\n");
                    } catch (Exception e) {
                        appendText("错误: 导入失败 " + e.getMessage() + "\n");
                    }
                }
            });

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

        adapter = new ScriptAdapter();
        lvScripts.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        btnSend.setOnClickListener(v -> {
            String input = etInput.getText().toString() + "\n";
            if (writer != null) {
                try {
                    writer.write(input);
                    writer.flush();
                    etInput.setText("");
                    appendText(">>> " + input);
                } catch (Exception e) {
                    appendText("错误: 输入失败 " + e.getMessage() + "\n");
                }
            }
        });
    }

    private class ScriptAdapter extends ArrayAdapter<String> {
        public ScriptAdapter() {
            super(MainActivity.this, 0, scriptList);
        }
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_script, parent, false);
            }
            String path = scriptList.get(position);
            String fileName = new File(path).getName();
            TextView tvName = convertView.findViewById(R.id.tvScriptName);
            Button btnRun = convertView.findViewById(R.id.btnRun);
            Button btnDelete = convertView.findViewById(R.id.btnDelete);
            tvName.setText(fileName);
            btnRun.setOnClickListener(v -> new Thread(() -> runElf(path)).start());
            btnDelete.setOnClickListener(v -> {
                scriptList.remove(position);
                notifyDataSetChanged();
                appendText("X 已移除脚本: " + fileName + "\n");
            });
            return convertView;
        }
    }

    // 🛠️ 新增：检测 Root 权限的方法
    private boolean checkRoot() {
        try {
            String suCmd = "su";
            String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
            for (String path : suPaths) {
                if (new File(path).exists()) { suCmd = path; break; }
            }
            
            // 执行 su -c id 来获取当前用户信息
            Process p = Runtime.getRuntime().exec(new String[]{suCmd, "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            p.waitFor();
            // 如果输出里包含 uid=0，说明获取到了真正的 Root 权限
            return sb.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private void runElf(String scriptPath) {
        try {
            // 1. 先检查 Root 权限
            if (!checkRoot()) {
                appendText("错误: 未获取到 Root 权限，请确认手机已 Root 并在弹窗中允许！\n");
                return;
            }
            appendText("\n√ 已获取 root 权限\n");
            appendText("√ busybox 已就绪\n");
            appendText("$ " + new File(scriptPath).getName() + "\n");

            String suCmd = "su";
            String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
            for (String path : suPaths) {
                if (new File(path).exists()) { suCmd = path; break; }
            }

            // 2. 将 stderr 合并到 stdout，防止读取线程卡死
            ProcessBuilder pb = new ProcessBuilder(suCmd, "-c", scriptPath);
            pb.redirectErrorStream(true);
            process = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // 3. 逐字符读取输出（完美解决不换行导致界面卡死的问题）
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    int c;
                    StringBuilder sb = new StringBuilder();
                    while ((c = reader.read()) != -1) {
                        if (c == '\n') {
                            final String line = sb.toString();
                            runOnUiThread(() -> appendText(line + "\n"));
                            sb.setLength(0);
                        } else {
                            sb.append((char) c);
                        }
                    }
                    // 输出最后一行（如果最后没有换行符）
                    if (sb.length() > 0) {
                        final String line = sb.toString();
                        runOnUiThread(() -> appendText(line));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> appendText("读取错误: " + e.getMessage() + "\n"));
                }
            }).start();

        } catch (Exception e) {
            appendText("执行异常: " + e.getMessage() + "\n");
        }
    }

    private void appendText(String text) {
        runOnUiThread(() -> {
            tvOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }
}
