private boolean extractAndPrepareBusybox() {

    try {

        File tempFile =
                new File(
                        getFilesDir(),
                        "busybox_temp"
                );

        // =====================================================
        // 1. 从 assets 提取 BusyBox
        // =====================================================

        try (
                InputStream is =
                        getAssets().open("busybox");

                FileOutputStream fos =
                        new FileOutputStream(tempFile)
        ) {

            byte[] buffer = new byte[8192];

            int len;

            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
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


        // =====================================================
        // 2. 目标路径
        //
        // 千万不要叫：
        //
        // root_launcher_busybox
        //
        // 必须叫：
        //
        // busybox
        // =====================================================

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


        // =====================================================
        // 3. 由 ROOT 完成复制和 chmod
        // =====================================================

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


        BufferedReader installReader =
                new BufferedReader(
                        new InputStreamReader(
                                installProcess.getInputStream()
                        )
                );


        StringBuilder installOutput =
                new StringBuilder();


        String line;


        while ((line =
                installReader.readLine()) != null) {

            installOutput.append(line);
            installOutput.append("\n");
        }


        int installExitCode =
                installProcess.waitFor();


        if (installExitCode != 0) {

            appendText(
                    "❌ BusyBox 安装命令失败\n"
            );

            appendText(
                    installOutput.toString()
            );

            return false;
        }


        appendText(
                installOutput.toString()
        );


        // =====================================================
        // 4. 不再使用 Java File.canExecute()
        //
        // 改成 ROOT 自己检查
        // =====================================================

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


        BufferedReader checkReader =
                new BufferedReader(
                        new InputStreamReader(
                                checkProcess.getInputStream()
                        )
                );


        StringBuilder checkOutput =
                new StringBuilder();


        while ((line =
                checkReader.readLine()) != null) {

            checkOutput.append(line);
            checkOutput.append("\n");
        }


        int checkExitCode =
                checkProcess.waitFor();


        if (checkExitCode != 0 ||
                !checkOutput
                        .toString()
                        .contains("BUSYBOX_EXEC_OK")) {

            appendText(
                    "❌ BusyBox 没有执行权限\n"
            );

            appendText(
                    "ROOT 检查结果:\n" +
                    checkOutput.toString()
            );

            return false;
        }


        // =====================================================
        // 5. 再真正执行一次 BusyBox --help
        //
        // 这样可以确认：
        //
        // 文件存在
        // 权限正确
        // ARM64 架构正确
        // ELF 可以被 Android 内核执行
        // =====================================================

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


        BufferedReader testReader =
                new BufferedReader(
                        new InputStreamReader(
                                testProcess.getInputStream()
                        )
                );


        StringBuilder testOutput =
                new StringBuilder();


        while ((line =
                testReader.readLine()) != null) {

            testOutput.append(line);
            testOutput.append("\n");
        }


        int testExitCode =
                testProcess.waitFor();


        if (testExitCode != 0) {

            appendText(
                    "❌ BusyBox 本体无法执行\n"
            );

            appendText(
                    testOutput.toString()
            );

            return false;
        }


        appendText(
                "√ BusyBox 执行测试成功\n"
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
