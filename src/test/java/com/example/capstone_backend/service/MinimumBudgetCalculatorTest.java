package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinimumBudgetCalculatorTest {
    private Cpu cpu(long id, long price, long score, String name, String socket) {
        Cpu value = new Cpu(); value.setId(id); value.setName(name);
        value.setPrice(price); value.setBenchScore(score); value.setSocketType(socket);
        return value;
    }
    private Gpu gpu(long price, long score, long requiredWatts) {
        Gpu value = new Gpu(); value.setId(2L); value.setPrice(price);
        value.setBenchScore(score); value.setRecommendedPower(requiredWatts);
        value.setGpuLength(230L); return value;
    }
    private Mainboard board(String socket, String size) {
        Mainboard value = new Mainboard(); value.setId(3L); value.setName("B760 MATX");
        value.setPrice(110_000L); value.setSocketType(socket);
        value.setMemoryType("DDR5"); value.setSize(size); return value;
    }
    private Ram ram(long capacity, long modules, long price) {
        Ram value = new Ram(); value.setId(capacity); value.setPrice(price);
        value.setMemoryType("DDR5"); value.setCapacity(capacity); value.setModuleCount(modules);
        return value;
    }
    private Ssd ssd(long capacity, long bench, long price) {
        Ssd value = new Ssd(); value.setId(5L); value.setCapacity(capacity);
        value.setBenchScore(bench); value.setPrice(price); return value;
    }
    private Power power(long watts, String size) {
        Power value = new Power(); value.setId(6L); value.setPrice(50_000L);
        value.setWattage(watts); value.setSize(size); return value;
    }
    private Case pcCase(String size) {
        Case value = new Case(); value.setId(7L); value.setPrice(30_000L);
        value.setSize(size); value.setGpuLength(290L); value.setCoolerLength(165L);
        return value;
    }
    private Cooler cooler() {
        Cooler value = new Cooler(); value.setId(8L); value.setPrice(20_000L);
        value.setSocketType("1700"); value.setCoolerLength(150L); return value;
    }
    private MinimumBudgetCalculator.Parts parts(Cpu cpu, Ram ram, Ssd ssd, Power psu) {
        return new MinimumBudgetCalculator.Parts(
                List.of(cpu), List.of(gpu(500_000L, 22_000L, 650L)),
                List.of(board("1700", "MATX")), List.of(ram), List.of(ssd),
                List.of(psu), List.of(pcCase("MATX")), List.of(cooler()));
    }

    @Test
    void officeUsesIntegratedGraphicsWithoutDiscreteGpu() {
        var data = parts(cpu(1, 180_000, 25_000, "인텔 코어i5-14세대 14400", "1700"),
                ram(16, 1, 100_000), ssd(500, 13_000, 95_000), power(500, "ATX"));
        var min = MinimumBudgetCalculator.find("OFFICE", data).orElseThrow();
        assertNull(min.gpuId());
        assertEquals(585_000, min.amount());
        assertEquals("OFFICE", min.usage());
    }

    @Test
    void gamingRequiresDiscreteGpuAndDualChannel() {
        var data = parts(cpu(1, 180_000, 25_000, "인텔 코어i5-14세대 14400", "1700"),
                ram(16, 2, 140_000), ssd(500, 13_000, 95_000), power(700, "ATX"));
        var min = MinimumBudgetCalculator.find("GAMING", data).orElseThrow();
        assertNotNull(min.gpuId());
        assertEquals(1_125_000, min.amount());
        assertTrue(MinimumBudgetCalculator.find("GAMING", parts(
                cpu(1, 180_000, 25_000, "인텔 코어i5-14세대 14400", "1700"),
                ram(16, 1, 100_000), ssd(500, 13_000, 95_000), power(700, "ATX"))).isEmpty());
    }

    @Test
    void videoRequires32GbAndFastOneTbSsd() {
        var cpu = cpu(1, 180_000, 30_000, "인텔 코어i5-14세대 14400", "1700");
        assertTrue(MinimumBudgetCalculator.find("VIDEO_EDITING", parts(
                cpu, ram(16, 2, 140_000), ssd(1000, 35_000, 200_000), power(700,"ATX"))).isEmpty());
        assertTrue(MinimumBudgetCalculator.find("VIDEO_EDITING", parts(
                cpu, ram(32, 2, 240_000), ssd(1000, 5_000, 200_000), power(700,"ATX"))).isEmpty());
        assertTrue(MinimumBudgetCalculator.find("VIDEO_EDITING", parts(
                cpu, ram(32, 2, 240_000), ssd(1000, 35_000, 200_000), power(700,"ATX"))).isPresent());
    }

    @Test
    void disallowsIncorrectSocketAndUnsafePowerFormFactor() {
        var base = parts(cpu(1, 180_000, 25_000, "인텔 코어i5-14세대 14400", "AM5"),
                ram(16, 2, 140_000), ssd(500, 13_000, 95_000), power(700,"ATX"));
        assertTrue(MinimumBudgetCalculator.find("GAMING", base).isEmpty());
        assertTrue(MinimumBudgetCalculator.find("GAMING", parts(
                cpu(1, 180_000, 25_000, "인텔 코어i5-14세대 14400", "1700"),
                ram(16, 2, 140_000), ssd(500, 13_000, 95_000), power(700,"TFX"))).isEmpty());
    }
}
