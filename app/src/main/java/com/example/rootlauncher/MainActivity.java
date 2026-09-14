/*
 * 通用 ELF 执行核心
 *
 * 支持：
 *   ELF32 / ELF64
 *   ET_EXEC / ET_DYN
 *   动态 ELF / 静态 ELF
 *   ARM / ARM64 / x86 / x86_64
 *
 * 不使用 Process.pid()
 * 不使用 BusyBox PTY 包装 ELF
 */

private static final int ELFCLASS32 = 1;
private static final int ELFCLASS64 = 2;

private static final int ET_EXEC = 2;
private static final int ET_DYN  = 3;

private static final int EM_386    = 3;
private static final int EM_ARM    = 40;
private static final int EM_X86_64 = 62;
private static final int EM_AARCH64 = 183;

private static final int PT_INTERP = 3;

private static class ElfInfo {
    boolean valid;
    boolean elf64;
    boolean dynamic;
    boolean hasInterp;

    int type;
    int machine;

    String machineName;
    String interpreter;

    String description() {
        StringBuilder sb = new StringBuilder();

        sb.append("ELF").append(elf64 ? "64" : "32");
        sb.append(" ");

        if (type == ET_EXEC) {
            sb.append("ET_EXEC");
        } else if (type == ET_DYN) {
            sb.append("ET_DYN");
        } else {
            sb.append("TYPE=").append(type);
        }

        sb.append(", ").append(machineName);

        if (dynamic) {
            sb.append(", dynamic");
        } else {
            sb.append(", static");
        }

        if (hasInterp) {
            sb.append(", interpreter=");
            sb.append(interpreter);
        }

        return sb.toString();
    }
}


/**
 * 从 ELF 文件头读取基本信息。
 *
 * 通过 root shell 的 od 读取，不依赖 Java 的 ELF 库。
 */
private ElfInfo inspectElf(String path) {
    ElfInfo info = new ElfInfo();

    try {
        Process p = new ProcessBuilder(
                findSu(),
                "-c",
                "od -An -tx1 -N64 '" + shellQuote(path) + "'"
        ).redirectErrorStream(true).start();

        BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream())
        );

        StringBuilder out = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) {
            out.append(line).append(" ");
        }

        p.waitFor();

        String hex = out.toString()
                .trim()
                .replaceAll("\\s+", " ");

        if (hex.length() < 8) {
            return info;
        }

        String[] b = hex.split(" ");

        if (b.length < 20) {
            return info;
        }

        // ELF magic
        if (!"7f".equalsIgnoreCase(b[0])
                || !"45".equalsIgnoreCase(b[1])
                || !"4c".equalsIgnoreCase(b[2])
                || !"46".equalsIgnoreCase(b[3])) {
            return info;
        }

        int clazz = hexByte(b[4]);
        int data = hexByte(b[5]);

        if (clazz != ELFCLASS32 && clazz != ELFCLASS64) {
            return info;
        }

        // 当前 Android 常见 ELF 基本按 little endian 处理。
        if (data != 1) {
            appendText("[!] ELF 是大端序，Android linker 通常无法直接加载\n");
            return info;
        }

        info.valid = true;
        info.elf64 = clazz == ELFCLASS64;

        // e_type offset 16
        int type = little16(b, 16);
        info.type = type;

        // e_machine offset 18
        int machine = little16(b, 18);
        info.machine = machine;

        switch (machine) {
            case EM_AARCH64:
                info.machineName = "AArch64";
                break;

            case EM_ARM:
                info.machineName = "ARM";
                break;

            case EM_X86_64:
                info.machineName = "x86_64";
                break;

            case EM_386:
                info.machineName = "x86";
                break;

            default:
                info.machineName = "machine=" + machine;
                break;
        }

        /*
         * 是否有 PT_INTERP。
         *
         * ELF64:
         *   e_phoff  = offset 32
         *   e_phentsize = offset 54
         *   e_phnum = offset 56
         *
         * ELF32:
         *   e_phoff  = offset 28
         *   e_phentsize = offset 42
         *   e_phnum = offset 44
         */
        long phoff;
        int phentsize;
        int phnum;

        if (info.elf64) {
            phoff = little64(b, 32);
            phentsize = little16(b, 54);
            phnum = little16(b, 56);
        } else {
            phoff = little32(b, 28);
            phentsize = little16(b, 42);
            phnum = little16(b, 44);
        }

        /*
         * 对于程序头数量比较大的 ELF，不直接在 Java 里猜。
         * 通过 root + readelf/od 后面继续判断。
         */
        if (phoff > 0 && phnum > 0 && phnum < 128
                && phentsize > 0 && phentsize < 256) {

            String cmd;

            if (info.elf64) {
                cmd =
                        "dd if='" + shellQuote(path)
                        + "' bs=1 skip=" + phoff
                        + " count=" + (phentsize * phnum)
                        + " 2>/dev/null | od -An -tx1";
            } else {
                cmd =
                        "dd if='" + shellQuote(path)
                        + "' bs=1 skip=" + phoff
                        + " count=" + (phentsize * phnum)
                        + " 2>/dev/null | od -An -tx1";
            }

            String ph = rootCommandOutput(cmd);

            if (ph != null && !ph.isEmpty()) {
                String[] pb = ph.trim()
                        .replaceAll("\\s+", " ")
                        .split(" ");

                int interpTypeSize = 4;

                for (int i = 0;
                     i + interpTypeSize <= pb.length;
                     i += phentsize) {

                    if (i + 4 > pb.length) {
                        break;
                    }

                    int pType = little32(pb, i);

                    if (pType == PT_INTERP) {
                        info.hasInterp = true;
                        break;
                    }
                }
            }
        }

        /*
         * 最可靠的判断：
         * 如果文件包含 .interp / PT_INTERP，就是动态 ELF。
         *
         * 对当前 Android ELF 来说：
         *   PT_INTERP 存在 -> linker
         *   PT_INTERP 不存在 -> 直接 exec
         */
        info.dynamic = info.hasInterp;

        /*
         * 再用 strings/grep 尝试获取 interpreter。
         */
        if (info.hasInterp) {
            String interp = rootCommandOutput(
                    "readelf -l '" + shellQuote(path)
                    + "' 2>/dev/null | grep -A1 INTERP"
            );

            if (interp != null) {
                if (interp.contains("/system/bin/linker64")) {
                    info.interpreter = "/system/bin/linker64";
                } else if (interp.contains("/system/bin/linker")) {
                    info.interpreter = "/system/bin/linker";
                } else if (interp.contains("/apex/")) {
                    /*
                     * Android 某些 ELF 可能使用 APEX linker。
                     * 这种情况下不要硬编码成 linker64。
                     */
                    String[] lines = interp.split("\\n");

                    for (String s : lines) {
                        if (s.contains("/apex/")
                                && s.contains("linker")) {
                            int idx = s.indexOf("/apex/");

                            if (idx >= 0) {
                                String x = s.substring(idx).trim();
                                int end = x.indexOf(" ");

                                if (end > 0) {
                                    x = x.substring(0, end);
                                }

                                info.interpreter = x;
                                break;
                            }
                        }
                    }
                }
            }

            if (info.interpreter == null) {
                if (info.elf64) {
                    info.interpreter = "/system/bin/linker64";
                } else {
                    info.interpreter = "/system/bin/linker";
                }
            }
        }

    } catch (Throwable e) {
        appendText("[!] ELF 检测失败："
                + e.getClass().getSimpleName()
                + ": "
                + e.getMessage()
                + "\n");
    }

    return info;
}


/**
 * 通用 ELF 启动。
 */
private void runElfReal(final String path) {
    new Thread(() -> {
        Process currentProcess = null;

        try {
            if (path == null || path.trim().isEmpty()) {
                appendText("[!] ELF 路径为空\n");
                return;
            }

            File file = new File(path);

            if (!file.exists()) {
                appendText("[!] ELF 不存在：\n" + path + "\n");
                return;
            }

            if (!file.isFile()) {
                appendText("[!] 不是普通文件：\n" + path + "\n");
                return;
            }

            appendText("\n========== ELF EXEC ==========\n");
            appendText("[+] 文件：" + path + "\n");

            /*
             * 由 root 设置执行权限。
             */
            String chmodResult = rootCommandOutput(
                    "chmod 755 '" + shellQuote(path) + "'"
            );

            if (chmodResult != null && !chmodResult.trim().isEmpty()) {
                appendText("[chmod] " + chmodResult + "\n");
            }

            ElfInfo elf = inspectElf(path);

            if (!elf.valid) {
                appendText("[!] 不是有效 ELF 文件\n");
                return;
            }

            appendText("[+] ELF：" + elf.description() + "\n");

            /*
             * 架构检查。
             *
             * 只做提示，不直接阻止未知架构。
             * 这样某些厂商/特殊 ELF 仍然有机会执行。
             */
            String abi = Build.SUPPORTED_ABIS != null
                    && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0]
                    : "unknown";

            appendText("[+] Android ABI：" + abi + "\n");

            String elfDir = file.getParent();

            if (elfDir == null || elfDir.isEmpty()) {
                elfDir = "/";
            }

            /*
             * 环境。
             *
             * 不设置一个过于严格的 LD_LIBRARY_PATH，
             * 防止破坏 Android linker 自己的 namespace。
             */
            String runtimeDir = RUNTIME_DIR;

            StringBuilder env = new StringBuilder();

            env.append("export PATH='")
                    .append(shellQuote(runtimeDir))
                    .append(":/data/local/tmp:/system/bin:/system/xbin:/vendor/bin:$PATH'; ");

            env.append("export HOME='")
                    .append(shellQuote(runtimeDir))
                    .append("'; ");

            env.append("export TMPDIR='")
                    .append(shellQuote(runtimeDir))
                    .append("'; ");

            env.append("cd '")
                    .append(shellQuote(elfDir))
                    .append("' || exit 126; ");

            /*
             * 最关键：
             *
             * 1. 动态 ELF：
             *      让 ELF 自己的 PT_INTERP / Android linker 处理。
             *
             * 2. 静态 ELF：
             *      直接 exec。
             *
             * 不使用 busybox script -q -c。
             */
            String command;

            if (elf.hasInterp) {
                appendText("[+] 类型：动态 ELF\n");
                appendText("[+] 使用 ELF 自带 interpreter\n");

                /*
                 * 这里仍然优先直接 exec。
                 *
                 * Linux kernel 会读取 PT_INTERP，
                 * Android linker 负责加载动态库。
                 */
                command = env.toString()
                        + "exec '"
                        + shellQuote(path)
                        + "'";
            } else {
                appendText("[+] 类型：静态 ELF / 无 PT_INTERP\n");
                appendText("[+] 直接 exec\n");

                command = env.toString()
                        + "exec '"
                        + shellQuote(path)
                        + "'";
            }

            appendText("[+] 正在启动...\n");

            ProcessBuilder pb = new ProcessBuilder(
                    findSu(),
                    "-c",
                    command
            );

            pb.redirectErrorStream(false);

            currentProcess = pb.start();

            process = currentProcess;
            elfRunning = true;

            final Process p = currentProcess;

            writer = new BufferedWriter(
                    new OutputStreamWriter(
                            p.getOutputStream()
                    )
            );

            final BufferedReader stdout =
                    new BufferedReader(
                            new InputStreamReader(
                                    p.getInputStream()
                            )
                    );

            final BufferedReader stderr =
                    new BufferedReader(
                            new InputStreamReader(
                                    p.getErrorStream()
                            )
                    );

            /*
             * stdout
             */
            new Thread(() -> {
                try {
                    String line;

                    while ((line = stdout.readLine()) != null) {
                        appendText(line + "\n");
                    }
                } catch (Throwable ignored) {
                }
            }, "ELF-stdout").start();

            /*
             * stderr
             *
             * 这个非常重要。
             *
             * Kairos 这类 ELF 如果被 linker/kernel/SELinux 拒绝，
             * 错误一般会从 stderr 出来。
             */
            new Thread(() -> {
                try {
                    String line;

                    while ((line = stderr.readLine()) != null) {
                        appendText("[stderr] " + line + "\n");
                    }
                } catch (Throwable ignored) {
                }
            }, "ELF-stderr").start();

            /*
             * 等待进程结束。
             */
            new Thread(() -> {
                try {
                    int code = p.waitFor();

                    appendText(
                            "\n[+] ELF 进程结束，exit code = "
                                    + code
                                    + "\n"
                    );

                } catch (Throwable e) {
                    appendText(
                            "\n[!] 等待 ELF 结束失败："
                                    + e.getMessage()
                                    + "\n"
                    );
                } finally {
                    elfRunning = false;

                    if (process == p) {
                        process = null;
                    }

                    if (writer != null) {
                        try {
                            writer.close();
                        } catch (Throwable ignored) {
                        }

                        writer = null;
                    }

                    appendText("========== EXEC END ==========\n");
                }
            }, "ELF-waiter").start();

        } catch (Throwable e) {
            elfRunning = false;

            appendText(
                    "[!] ELF 启动异常："
                            + e.getClass().getName()
                            + "\n"
            );

            if (e.getMessage() != null) {
                appendText(
                        "[!] "
                                + e.getMessage()
                                + "\n"
                );
            }

            /*
             * 不使用 Process.pid()。
             */
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }

            process = null;
        }
    }, "ELF-launcher").start();
}


/**
 * shell 单引号转义。
 *
 * 注意：
 * shellQuote(path) 返回的是：
 * 'xxx'
 */
private String shellQuote(String s) {
    if (s == null) {
        return "''";
    }

    return "'" + s.replace("'", "'\\''") + "'";
}


/**
 * 执行 root command 并读取输出。
 */
private String rootCommandOutput(String command) {
    Process p = null;

    try {
        p = new ProcessBuilder(
                findSu(),
                "-c",
                command
        ).redirectErrorStream(true).start();

        BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        p.getInputStream()
                )
        );

        StringBuilder sb = new StringBuilder();

        String line;

        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }

        p.waitFor();

        return sb.toString();

    } catch (Throwable e) {
        return "";
    } finally {
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}


/**
 * 十六进制 byte。
 */
private int hexByte(String s) {
    return Integer.parseInt(s, 16);
}


/**
 * ELF little-endian 16 bit。
 */
private int little16(String[] b, int off) {
    if (off + 2 > b.length) {
        return 0;
    }

    return hexByte(b[off])
            | (hexByte(b[off + 1]) << 8);
}


/**
 * ELF little-endian 32 bit。
 */
private long little32(String[] b, int off) {
    if (off + 4 > b.length) {
        return 0;
    }

    return ((long) hexByte(b[off]))
            | ((long) hexByte(b[off + 1]) << 8)
            | ((long) hexByte(b[off + 2]) << 16)
            | ((long) hexByte(b[off + 3]) << 24);
}


/**
 * ELF little-endian 64 bit。
 */
private long little64(String[] b, int off) {
    if (off + 8 > b.length) {
        return 0;
    }

    long v = 0;

    for (int i = 0; i < 8; i++) {
        v |= ((long) hexByte(b[off + i])) << (i * 8);
    }

    return v;
}


