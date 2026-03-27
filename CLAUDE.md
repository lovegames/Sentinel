# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建和测试命令

这是一个基于 Maven 的 Java 项目，目标 JDK 8+（使用 JDK 17 构建）。

```bash
# 运行所有测试
mvn test

# 使用指定 JDK 版本运行测试（需配置 toolchains.xml）
mvn test -Dsurefire.jdk-toolchain-version=8

# 跳过测试构建
mvn install -DskipTests=true -Dmaven.javadoc.skip=true

# 运行单个测试类
mvn test -Dtest=FlowRuleCheckerTest

# 运行单个测试方法
mvn test -Dtest=FlowRuleCheckerTest#testFlowRuleChecker

# PMD 检查（阿里巴巴 Java 编码规范）
mvn pmd:check
```

JDK < 17 时，跳过 Spring 6.x 测试：`-Dskip.spring.v6x.test=true`

## 项目结构

Sentinel 是一个面向微服务的流量控制和熔断库。采用插槽链架构，每个插槽负责特定职责。

### 核心模块

- **sentinel-core**: 核心库，包含插槽链、规则管理和统计
  - 入口点: `SphU.entry()`, `SphO.entry()`, `Tracer.trace()`
  - 插槽链: 通过 SPI 构建，配置在 `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot`

- **sentinel-extension**: 数据源（Nacos、Zookeeper、Apollo 等）、注解支持、热点参数限流

- **sentinel-adapter**: 框架适配器（Dubbo、Spring WebMVC、gRPC、Quarkus 等）

- **sentinel-transport**: 与 Sentinel Dashboard 通信（simple-http、netty-http、spring-mvc）

- **sentinel-cluster**: 集群流控（客户端/服务端）

- **sentinel-dashboard**: Spring Boot Web 控制台，用于监控和规则配置

### 默认插槽链（按顺序）

1. `NodeSelectorSlot` - 构建调用树（每个上下文创建 DefaultNode）
2. `ClusterBuilderSlot` - 为资源统计构建 ClusterNode
3. `LogSlot` - 记录指标日志
4. `StatisticSlot` - 记录 QPS、RT、线程数、异常数
5. `AuthoritySlot` - 黑白名单控制
6. `SystemSlot` - 系统自适应保护
7. `FlowSlot` - 流量控制（QPS、线程数）
8. `DegradeSlot` - 熔断降级（RT、异常比例/数量）
9. `DefaultCircuitBreakerSlot` - 新版熔断器实现

### 核心 API

```java
// 保护资源
try (Entry entry = SphU.entry("resourceName")) {
    // 业务逻辑
} catch (BlockException e) {
    // 处理被限流/熔断的情况
}

// 记录业务异常
Tracer.trace(businessException);

// 加载流控规则
List<FlowRule> rules = Collections.singletonList(
    new FlowRule("resourceName").setCount(20).setGrade(RuleConstant.FLOW_GRADE_QPS)
);
FlowRuleManager.loadRules(rules);
```

### SPI 扩展机制

Sentinel 使用自定义 SPI（`com.alibaba.csp.sentinel.spi.SpiLoader`）实现扩展：

- 添加自定义插槽：实现 `ProcessorSlot` 并在 `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot` 中注册
- 自定义插槽链构建器：实现 `SlotChainBuilder` 并通过 `@Spi(isDefault = true)` 注册
- 数据源扩展：在 sentinel-extension 中继承 `AbstractDataSource`

### 测试

测试使用 JUnit 4、Mockito 和 AssertJ。涉及时间的测试可继承 `AbstractTimeBasedTest` 来控制时间。