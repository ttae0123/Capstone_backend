package com.example.capstone_backend.service;

import com.example.capstone_backend.entity.RecommendationBudgetRatio;
import com.example.capstone_backend.repository.RecommendationBudgetRatioRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BudgetRatioServiceTest {

    @Test
    void selectsNearestBudgetAndMapsRatios() throws Exception {
        RecommendationBudgetRatio lower = row("GAMING", 1_500_000L,
                "0.125990", "0.461557", "0.072560", "0.146791", "0.112030", "0.032811", "0.031365", "0.016896");
        RecommendationBudgetRatio upper = row("GAMING", 2_000_000L,
                "0.125836", "0.397148", "0.070341", "0.195302", "0.140561", "0.031037", "0.025297", "0.014478");
        BudgetRatioService service = new BudgetRatioService(repository(List.of(lower, upper)));

        var selected = service.resolve("GAMING", 1_800_000L);
        assertEquals(2_000_000L, selected.referenceBudget());
        assertEquals(0.397148, selected.gpu(), 1e-10);
        assertEquals(0.195302, selected.ram(), 1e-10);

        assertEquals(1_500_000L, service.resolve("GAMING", 1_750_000L).referenceBudget());
        assertEquals(1_500_000L, service.resolve("GAMING", 100_000L).referenceBudget());
        assertEquals(2_000_000L, service.resolve("GAMING", 9_000_000L).referenceBudget());
    }

    @Test
    void videoEditingAndWorkUseSameRatios() throws Exception {
        RecommendationBudgetRatio video = row("VIDEO_EDITING", 2_000_000L,
                "0.134772", "0.377291", "0.070341", "0.202252", "0.144532", "0.031037", "0.025297", "0.014478");
        BudgetRatioService service = new BudgetRatioService(repository(List.of(video)));
        assertEquals(0.377291, service.resolve("VIDEO_EDITING", 2_000_000L).gpu(), 1e-10);
        assertEquals(0.377291, service.resolve("work", 2_000_000L).gpu(), 1e-10);
    }

    @Test
    void officeKeepsZeroDiscreteGpu() throws Exception {
        RecommendationBudgetRatio office = row("OFFICE", 500_000L,
                "0.348861", "0.000000", "0.131313", "0.207211", "0.215777", "0.062118", "0.031326", "0.003394");
        BudgetRatioService service = new BudgetRatioService(repository(List.of(office)));
        assertEquals(0.0, service.resolve("office", 500_000L).gpu());
    }

    @Test
    void failsClearlyWithoutBudgetRows() {
        BudgetRatioService service = new BudgetRatioService(repository(List.of()));
        assertThrows(IllegalStateException.class, () -> service.resolve("GAMING", 2_000_000L));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("INVALID", 2_000_000L));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("GAMING", 0));
    }

    private static RecommendationBudgetRatio row(String usage, long budget, String... values) throws Exception {
        RecommendationBudgetRatio row = new RecommendationBudgetRatio();
        set(row, "usage", usage);
        set(row, "budget", budget);
        String[] fields = {"cpuRatio", "gpuRatio", "mainboardRatio", "ramRatio", "ssdRatio", "powerRatio", "caseRatio", "coolerRatio"};
        for (int i = 0; i < fields.length; i++) {
            set(row, fields[i], new BigDecimal(values[i]));
        }
        return row;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RecommendationBudgetRatioRepository repository(List<RecommendationBudgetRatio> values) {
        return (RecommendationBudgetRatioRepository) Proxy.newProxyInstance(
                RecommendationBudgetRatioRepository.class.getClassLoader(),
                new Class<?>[]{RecommendationBudgetRatioRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByUsageOrderByBudgetAsc")) {
                        return values.stream()
                                .filter(row -> row.getUsage().equals(args[0]))
                                .sorted(Comparator.comparingLong(RecommendationBudgetRatio::getBudget))
                                .toList();
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
