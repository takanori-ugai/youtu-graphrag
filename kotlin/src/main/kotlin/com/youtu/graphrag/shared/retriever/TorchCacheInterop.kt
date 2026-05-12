package com.youtu.graphrag.shared.retriever

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

internal object TorchCacheInterop {
    private val logger = KotlinLogging.logger {}

    fun tryConvertPtToNpz(
        ptPath: Path,
        npzPath: Path,
        pythonExecutable: String = "python3",
        runner: ((List<String>) -> Int)? = null,
    ): Boolean {
        if (!ptPath.exists()) {
            return false
        }
        npzPath.parent?.createDirectories()

        val script =
            """
            import sys
            import numpy as np
            try:
                import torch
            except Exception:
                sys.exit(11)
            pt_path = sys.argv[1]
            npz_path = sys.argv[2]
            try:
                try:
                    cache = torch.load(pt_path, map_location='cpu', weights_only=False)
                except TypeError:
                    cache = torch.load(pt_path, map_location='cpu')
                if not isinstance(cache, dict):
                    sys.exit(12)
                out = {}
                for key, value in cache.items():
                    if value is None:
                        continue
                    if hasattr(value, 'detach'):
                        arr = value.detach().cpu().numpy()
                    else:
                        arr = np.array(value, dtype=np.float32)
                    arr = np.asarray(arr, dtype=np.float32).reshape(-1)
                    out[str(key)] = arr
                if not out:
                    sys.exit(13)
                np.savez_compressed(npz_path, **out)
            except Exception:
                sys.exit(14)
            """.trimIndent()

        val command = listOf(pythonExecutable, "-c", script, ptPath.toString(), npzPath.toString())
        val exitCode = runCommand(command, runner)
        val converted = exitCode == 0 && npzPath.exists()
        if (!converted) {
            logger.debug { "PT->NPZ conversion failed for $ptPath (exitCode=$exitCode)" }
        }
        return converted
    }

    fun tryConvertNpzToPt(
        npzPath: Path,
        ptPath: Path,
        pythonExecutable: String = "python3",
        runner: ((List<String>) -> Int)? = null,
    ): Boolean {
        if (!npzPath.exists()) {
            return false
        }
        ptPath.parent?.createDirectories()

        val script =
            """
            import sys
            import numpy as np
            try:
                import torch
            except Exception:
                sys.exit(11)
            npz_path = sys.argv[1]
            pt_path = sys.argv[2]
            try:
                out = {}
                with np.load(npz_path) as cache:
                    for key in cache.files:
                        arr = np.asarray(cache[key], dtype=np.float32).reshape(-1)
                        out[str(key)] = torch.from_numpy(arr).float()
                if not out:
                    sys.exit(13)
                torch.save(out, pt_path)
            except Exception:
                sys.exit(14)
            """.trimIndent()

        val command = listOf(pythonExecutable, "-c", script, npzPath.toString(), ptPath.toString())
        val exitCode = runCommand(command, runner)
        val converted = exitCode == 0 && ptPath.exists()
        if (!converted) {
            logger.debug { "NPZ->PT conversion failed for $npzPath (exitCode=$exitCode)" }
        }
        return converted
    }

    private fun runCommand(
        command: List<String>,
        runner: ((List<String>) -> Int)?,
    ): Int {
        if (runner != null) {
            return runCatching { runner(command) }.getOrDefault(-1)
        }

        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) {
                    // drain output to avoid process blocking on full buffers
                }
            }
            process.waitFor()
        }.getOrElse {
            -1
        }
    }
}
