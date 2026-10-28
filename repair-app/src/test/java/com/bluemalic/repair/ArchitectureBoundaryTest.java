package com.bluemalic.repair;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轻量模块边界守卫：只查“绝对不该出现”的依赖方向，不替代 ArchUnit。
 *
 * <p>没有引入 ArchUnit 是有意的——加新依赖要先过项目约定；先用源码扫描把最容易退化的
 * 三条边界钉住：controller 不碰 service.impl、entity 不碰 service/mapper/controller、
 * mapper 不碰 service/controller。等边界继续变多再评估 ArchUnit。
 */
class ArchitectureBoundaryTest {

    private static final Path ROOT = Paths.get("src/main/java/com/bluemalic/repair");

    @Test
    void controllerDoesNotDependOnServiceImpl() {
        assertNoImport("controller", "com.bluemalic.repair.service.impl");
    }

    @Test
    void entityDoesNotDependOnServiceMapperOrController() {
        assertNoImport("entity", "com.bluemalic.repair.service");
        assertNoImport("entity", "com.bluemalic.repair.mapper");
        assertNoImport("entity", "com.bluemalic.repair.controller");
    }

    @Test
    void mapperDoesNotDependOnServiceOrController() {
        assertNoImport("mapper", "com.bluemalic.repair.service");
        assertNoImport("mapper", "com.bluemalic.repair.controller");
    }

    @Test
    void serviceDoesNotDependOnController() {
        assertNoImport("service", "com.bluemalic.repair.controller");
    }

    private void assertNoImport(String packageName, String forbiddenImportPrefix) {
        Path packageRoot = ROOT.resolve(packageName);
        assertThat(packageRoot).as("源码目录不存在：%s", packageRoot).exists();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String source = read(path);
                        if (source.contains("import " + forbiddenImportPrefix)) {
                            violations.add(ROOT.relativize(path) + " -> " + forbiddenImportPrefix);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(violations)
                .as("%s 下不许依赖 %s", packageName, forbiddenImportPrefix)
                .isEmpty();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
