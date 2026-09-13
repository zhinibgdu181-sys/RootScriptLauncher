package com.example.rootlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;
    private ArrayList<String> scriptList = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private Process process;
    private BufferedWriter writer;

    // 接收文件选择结果
    private final androidx.activity.result.ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String fileName = "script_" + System.currentTimeMillis() + ".sh";
                    File destFile = new File(getFilesDir(), fileName);
                    try {
                        // 复制文件到 App 私有目录（绕过 noexec 限制）
                        InputStream is = getContentResolver().openInputStream(uri);
                        FileOutputStream fos = new FileOutputStream(destFile);
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                        is.close(); fos.close();
                        
                        // 赋权
                        Runtime.getRuntime().exec("chmod 755 " + destFile.getAbsolutePath()).waitFor();
                        
                        scriptList.add(destFile.getAbsolutePath());
                        adapter.notifyDataSetChanged();
                        appendText("已添加脚本: " + destFile.getName() + "\n");
                    } catch (Exception e) {
                        appendText("导入失败: " + e.getMessage() + "\n");
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

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, scriptList);
        lvScripts.setAdapter(adapter);

        // 点击添加，打开系统文件管理器
        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        // 点击列表中的脚本直接运行
        lvScripts.setOnItemClickListener((parent, view, position, id) -> {
            String scriptPath = scriptList.get(position);
            new Thread(() -> runElf(scriptPath)).start();
        });

        // 发送输入内容
        btnSend.setOnClickListener(v -> {
            String input = etInput.getText().toString() + "\n";
            if (writer != null) {
                try {
                    writer.write(input);
                    writer.flush();
                    etInput.setText("");
                    appendText(">>> " + input);
                } catch (Exception e) {
                    appendText("输入失败: " + e.getMessage() + "\n");
                }
            }
        });
    }

    // 执行脚本并交互
    private void runElf(String scriptPath) {
        try {
            appendText("\n正在申请 Root 权限运行: " + new File(scriptPath).getName() + "\n");
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", scriptPath});
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String out = line;
                runOnUiThread(() -> appendText(out + "\n"));
            }
            
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errReader.readLine()) != null) {
                final String err = line;
                runOnUiThread(() -> appendText("错误: " + err + "\n"));
            }
        } catch (Exception e) {
            runOnUiThread(() -> appendText("执行异常: " + e.getMessage() + "\n"));
        }
    }

    private void appendText(String text) {
        tvOutput.append(text);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }
}
