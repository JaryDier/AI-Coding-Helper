package com.singleagent.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/skill")
@RequiredArgsConstructor
@Slf4j
public class SkillController {

    @Value("${skills.upload.path}")
    private String skillRootPath;

    private static final long MAX_UNZIPPED_BYTES = 200L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRY_COUNT = 2000;

    @PostMapping("/upload")
    public String uploadSkill(@RequestParam(name = "file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.error("File is empty");
            return "File is empty";
        }

        try {
            Path skillDirectory = buildSkillDirectory(file);
            Files.createDirectories(skillDirectory);
            unZip(skillDirectory, file);
            return "success";
        } catch (IllegalArgumentException e) {
            log.error("upload skill param invalid: {}", e.getMessage());
            return e.getMessage();
        } catch (IOException e) {
            log.error("upload skill failed", e);
            return "upload skill failed: " + e.getMessage();
        }
    }

    @PostMapping("/upload/mulitFile")
    public String uploadMulitFile(@RequestParam(name = "files") MultipartFile[] multipartFiles) {
        if (multipartFiles == null || multipartFiles.length == 0) {
            log.error("multipartFiles is empty");
            return "multipartFiles is empty";
        }

        for (MultipartFile multipartFile : multipartFiles) {
            String result = uploadSkill(multipartFile);
            if (!"success".equals(result)) {
                return result;
            }
        }
        return "success";
    }

    public boolean unZip(Path skillDirectory, MultipartFile file) throws IOException {
        Path targetRoot = skillDirectory.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);

        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            int entryCount = 0;
            long totalUnzippedBytes = 0;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRY_COUNT) {
                    throw new IOException("zip entry count exceeds limit");
                }

                String entryName = entry.getName();
                log.info("zip entry name = {}", entryName);
                Path outPath = targetRoot.resolve(entryName).normalize();
                if (!outPath.startsWith(targetRoot)) {
                    throw new IOException("zip entry is outside target directory: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                    zipInputStream.closeEntry();
                    continue;
                }

                Path parentDir = outPath.getParent();
                if (parentDir != null) {
                    Files.createDirectories(parentDir);
                }

                try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(outPath))) {
                    byte[] bytes = new byte[2048];
                    int len;
                    while ((len = zipInputStream.read(bytes)) != -1) {
                        totalUnzippedBytes += len;
                        if (totalUnzippedBytes > MAX_UNZIPPED_BYTES) {
                            throw new IOException("zip uncompressed size exceeds limit");
                        }
                        bos.write(bytes, 0, len);
                    }
                }
                zipInputStream.closeEntry();
            }
            return true;
        }
    }

    private Path buildSkillDirectory(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (StringUtils.isBlank(originalFilename)) {
            throw new IllegalArgumentException("originalFilename is empty");
        }

        String safeOriginalFilename = getSafeFileName(originalFilename);
        if (!safeOriginalFilename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Only zip files are supported");
        }

        String skillName = sanitizeSkillName(safeOriginalFilename.substring(0, safeOriginalFilename.length() - 4));
        if (StringUtils.isBlank(skillName)) {
            throw new IllegalArgumentException("skill name is empty");
        }

        Path rootPath = Path.of(skillRootPath).toAbsolutePath().normalize();
        Path skillDirectory = rootPath.resolve(skillName).normalize();
        if (!skillDirectory.startsWith(rootPath)) {
            throw new IllegalArgumentException("Invalid skill path");
        }
        return skillDirectory;
    }

    private String getSafeFileName(String originalFilename) {
        String normalized = originalFilename.replace("\\", "/");
        int index = normalized.lastIndexOf("/");
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String sanitizeSkillName(String skillName) {
        return skillName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
