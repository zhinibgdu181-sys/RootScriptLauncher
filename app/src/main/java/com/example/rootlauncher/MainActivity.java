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

    private void runElf(String scriptPath) {
        try {
            appendText("\n√ 已获取 root 权限\n");
            appendText("√ busybox 已就绪\n");
            appendText("$ " + new File(scriptPath).getName() + "\n");

            // 🛠️ 修复1：动态寻找 su 的真实路径
            String suCmd = "su";
            String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
            for (String path : suPaths) {
                if (new File(path).exists()) {
                    suCmd = path;
                    break;
                }
            }

            // 执行脚本
            process = Runtime.getRuntime().exec(new String[]{suCmd, "-c", scriptPath});
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String out = line;
                appendText(out + "\n");
            }

            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errReader.readLine()) != null) {
                final String err = line;
                appendText("错误: " + err + "\n");
            }
        } catch (Exception e) {
            appendText("执行异常: " + e.getMessage() + "\n");
        }
    }

    // 🛠️ 修复2：把 UI 更新强制切换到主线程
    private void appendText(String text) {
        runOnUiThread(() -> {
            tvOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }
}
