package com.liwx.aiassistant.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liwx.aiassistant.agent.tool.RagTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 质量评估测试（Evaluation）
 *
 * 三种用法：
 *   1. shouldEvaluateRagAnswerQuality：单次评估，跑一个问题看效果
 *   2. shouldBatchEvaluateFromQuestionFile：批量评估，读 JSON 问题集，输出报告到文件
 *   3. shouldBatchEvaluateWithBaseline：批量评估 + 指定基线对比
 *
 * 批量评估的用法：
 *   改参数前跑一次 → 报告存到 logs/rag-eval-时间戳.txt
 *   改完参数再跑一次 → 自动读上一次结果做对比，输出提升/下降
 *
 * 指定基线对比：
 *   -Dbaseline=rag-eval-20260811-153000.txt 指定跟哪次比
 *   不传则自动选 logs/ 下最新的报告做基线
 *
 * 问题集在 src/test/resources/rag-eval-questions.json，随时加问题不用改代码
 */
@SpringBootTest
@Tag("integration")  // 集成测试：需 MySQL/Milvus/API Key + 完整 Spring 上下文，mvn test 默认排除，手动跑：mvn test -Dgroups=integration
class RagEvaluationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    /**
     * 检索入口用 RagTool（生产链路）而不是直连 vectorStore：
     * RagTool 包含 查询改写 → 混合检索（向量+关键词）→ Rerank 完整链路，
     * 评估它才是评估用户真实体验到的检索质量；直连 vectorStore 只能测裸向量召回。
     * 改写开关在 application.yml 的 rag.query-rewrite.enabled，跑基线时设 false
     */
    @Autowired
    private RagTool ragTool;

    private ChatClient chatClient;
    private RelevancyEvaluator relevancyEvaluator;

    @BeforeEach
    void setUp() {
        this.chatClient = chatClientBuilder.build();
        // RelevancyEvaluator 需要一个 ChatClient.Builder 来调 LLM 做评估
        this.relevancyEvaluator = new RelevancyEvaluator(chatClientBuilder);
    }

    /**
     * 评估：检索资料 → 生成回答 → 判断回答是否基于资料
     *
     * 完整 RAG 链路（与生产一致）：
     *   1. 用户问题 → RagTool（改写+混合检索+Rerank）拿 top3 资料
     *   2. 资料 + 问题 → LLM 生成回答
     *   3. RelevancyEvaluator 评估：回答是否真的来自检索资料（防幻觉）
     */
    @Test
    void shouldEvaluateRagAnswerQuality() {
        String userQuestion = "公司的请假流程是什么？";

        // 1. 走生产检索链路：查询改写 → 混合检索 → Rerank，返回拼接好的【参考资料N】文本
        String context = ragTool.searchKnowledge(userQuestion);

        System.out.println("========== RAG 质量评估 ==========");
        System.out.println("用户问题：" + userQuestion);
        System.out.println(context.isBlank() ? "未检索到资料" : "检索资料：\n" + context);

        String answer = chatClient.prompt()
                .system("根据以下参考资料回答用户问题。如果资料中没有答案，请明确告知。" +
                        "不要编造信息。\n\n参考资料：\n" + context)
                .user(userQuestion)
                .call()
                .content();

        // 3. 评估回答是否基于检索资料（防幻觉）
        EvaluationRequest evalRequest = new EvaluationRequest(userQuestion, answer);
        EvaluationResponse evalResponse = relevancyEvaluator.evaluate(evalRequest);

        System.out.println("AI 回答：" + answer);
        System.out.println("评估通过：" + evalResponse.isPass());
        System.out.println("评分：" + evalResponse.getScore());
        System.out.println("反馈：" + evalResponse.getFeedback());
        System.out.println("==================================");

        assertNotNull(answer, "回答不应为 null");
        assertNotNull(evalResponse, "评估结果不应为 null");
        assertTrue(evalResponse.getScore() >= 0, "评分应大于等于 0");
    }

    // ========== 批量评估：读 JSON 问题集 → 逐个评估 → 自动对比上次结果 → 输出报告 ==========

    private static final File REPORT_DIR = new File("logs");

    /**
     * 批量评估：读 JSON 问题集 → 逐个评估 → 自动对比上次结果 → 输出报告
     *
     * 用法：
     *   改参数前跑一次（建立基线）
     *   改完参数再跑一次（自动对比，直接看提升/下降）
     *
     * 不用手动对比文件，代码自动找 logs/ 下最新的报告做基线
     * 想指定基线？用 shouldBatchEvaluateWithBaseline（传 -Dbaseline=文件名）
     */
    @Test
    void shouldBatchEvaluateFromQuestionFile() throws Exception {
        runBatchEvaluation(null);
    }

    /**
     * 批量评估 + 指定基线文件对比
     *
     * 用法：在 IDEA 的 Run Configuration → VM options 里加：
     *   -Dbaseline=rag-eval-20260811-153000.txt
     *
     * 这样就会跟指定的那次结果对比，而不是自动选最新的
     */
    @Test
    void shouldBatchEvaluateWithBaseline() throws Exception {
        String baseline = System.getProperty("baseline");
        if (baseline == null || baseline.isBlank()) {
            System.out.println("未指定 -Dbaseline 参数，将自动选择最近的报告做基线");
            System.out.println("提示：在 VM options 里加 -Dbaseline=rag-eval-20260811-153000.txt 可指定基线");
        }
        runBatchEvaluation(baseline);
    }

    /**
     * 批量评估核心逻辑
     *
     * @param baselineFile 指定的基线文件名（如 rag-eval-20260811-153000.txt），传 null 则自动选最新的
     */
    private void runBatchEvaluation(String baselineFile) throws Exception {
        // 0. 找基线文件：指定了就用指定的，没指定就自动选 logs/ 下最新的
        File baseline = findBaselineFile(baselineFile);
        Map<String, Float> previousScores = loadScoresFromFile(baseline);
        String baselineName = (baseline != null) ? baseline.getName() : "无（首次评估）";

        // 1. 读取问题集 JSON
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> questions = mapper.readValue(
                new File("src/test/resources/rag-eval-questions.json"),
                new TypeReference<>() {});

        System.out.println("========== 批量 RAG 质量评估 ==========");
        System.out.println("对比基准：" + baselineName);
        System.out.println("共 " + questions.size() + " 个测试问题");
        System.out.println();
    
        // 2. 逐个评估
        List<String> reportLines = new ArrayList<>();
        reportLines.add("RAG 批量评估报告");
        reportLines.add("生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        reportLines.add("测试问题数：" + questions.size());
        reportLines.add("=" .repeat(60));
    
        int passCount = 0;
        float totalScore = 0;
        float prevTotalScore = 0;
        int improvedCount = 0;
        int declinedCount = 0;
    
        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i).get("question");
            String desc = questions.get(i).getOrDefault("description", "");
    
            System.out.println("[" + (i + 1) + "/" + questions.size() + "] " + question);
    
            EvaluationResponse evalResponse = evaluateSingleQuestion(question);
            float score = evalResponse.getScore();
            boolean pass = evalResponse.isPass();
    
            if (pass) passCount++;
            totalScore += score;
    
            // 和上次对比
            String diff = "";
            Float prevScore = previousScores.get(question);
            if (prevScore != null) {
                prevTotalScore += prevScore;
                float delta = score - prevScore;
                if (delta > 0.01f) { diff = " ↑" + String.format("+%.2f", delta); improvedCount++; }
                else if (delta < -0.01f) { diff = " ↓" + String.format("%.2f", delta); declinedCount++; }
                else { diff = " → 持平"; }
            }
    
            String line = String.format("[%d] %s (%s) | pass=%s | score=%.2f%s | %s",
                    i + 1, question, desc, pass, score, diff, evalResponse.getFeedback());
            reportLines.add(line);
            System.out.println("  → score=" + String.format("%.2f", score) + " pass=" + pass + diff);
            System.out.println();
        }
    
        // 3. 输出汇总报告
        float avgScore = totalScore / questions.size();
        StringBuilder summary = new StringBuilder();
        summary.append("\n").append("=" .repeat(60)).append("\n");
        summary.append("批量评估汇总\n");
        summary.append(String.format("通过率：%d/%d (%.0f%%)%n", passCount, questions.size(), passCount * 100.0 / questions.size()));
        summary.append(String.format("平均分：%.2f%n", avgScore));
    
        if (!previousScores.isEmpty()) {
            float prevAvg = prevTotalScore / questions.size();
            float avgDelta = avgScore - prevAvg;
            summary.append("\n----- 与基线对比 -----\n");
            summary.append(String.format("基线：%s%n", baselineName));
            summary.append(String.format("基线平均分：%.2f → 本次：%.2f（%s%.2f）%n",
                    prevAvg, avgScore, avgDelta >= 0 ? "↑" : "↓", avgDelta));
            summary.append(String.format("提升：%d 题 | 下降：%d 题 | 持平：%d 题%n",
                    improvedCount, declinedCount, questions.size() - improvedCount - declinedCount));
        }
        summary.append("=" .repeat(60));
    
        System.out.println(summary);
    
        // 4. 只保存一份带时间戳的报告（不覆盖任何历史文件）
        if (!REPORT_DIR.exists()) REPORT_DIR.mkdirs();
        List<String> fullReport = new ArrayList<>(reportLines);
        fullReport.add(summary.toString());
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File reportFile = new File(REPORT_DIR, "rag-eval-" + timestamp + ".txt");
        Files.write(reportFile.toPath(), fullReport);
    
        System.out.println("\n报告已保存：" + reportFile.getName());
        if (previousScores.isEmpty()) {
            System.out.println("这是第一次评估，已建立基线。改完参数后再跑一次会自动对比。");
        }
    }
    
    /**
     * 找基线文件：指定了就用指定的，没指定就自动选 logs/ 下最新的 rag-eval-*.txt
     */
    private File findBaselineFile(String specifiedName) {
        if (!REPORT_DIR.exists()) return null;

        // 指定了文件名
        if (specifiedName != null && !specifiedName.isBlank()) {
            File specified = new File(REPORT_DIR, specifiedName);
            if (specified.exists()) return specified;
            System.out.println("警告：指定的基线文件 " + specifiedName + " 不存在，将自动选择最新的");
        }

        // 自动选最新的 rag-eval-*.txt（按文件名排序，时间戳大的就是最新的）
        File[] files = REPORT_DIR.listFiles((dir, name) ->
                name.startsWith("rag-eval-") && name.endsWith(".txt"));
        if (files == null || files.length == 0) return null;

        File latest = files[0];
        for (File f : files) {
            if (f.getName().compareTo(latest.getName()) > 0) latest = f;
        }
        return latest;
    }

    /**
     * 从报告文件中解析每个问题的分数
     * 报告格式：[1] 问题 (描述) | pass=true | score=0.85 | ...
     */
    private Map<String, Float> loadScoresFromFile(File reportFile) {
        Map<String, Float> scores = new HashMap<>();
        if (reportFile == null || !reportFile.exists()) return scores;

        try {
            List<String> lines = Files.readAllLines(reportFile.toPath());
            for (String line : lines) {
                // 匹配格式：score=0.85
                int scoreIdx = line.indexOf("score=");
                if (scoreIdx < 0) continue;
                // 找到问题文本（[数字] 后面到 | 之前）
                int bracketEnd = line.indexOf(']');
                int pipeIdx = line.indexOf('|');
                if (bracketEnd < 0 || pipeIdx < 0) continue;
                String question = line.substring(bracketEnd + 2, pipeIdx).trim();
                // 解析 score
                String scorePart = line.substring(scoreIdx + 6).split("[\\s|]")[0];
                // 去掉可能附加的 ↑↓→ 符号
                scorePart = scorePart.replaceAll("[↑↓→].*", "").trim();
                scores.put(question, Float.parseFloat(scorePart));
            }
        } catch (Exception e) {
            // 解析失败不影响本次评估
        }
        return scores;
    }

    /**
     * 单个问题的完整 RAG 评估流程：检索（RagTool 生产链路）→ 生成 → 评估
     */
    private EvaluationResponse evaluateSingleQuestion(String userQuestion) {
        // 1. 走生产检索链路：查询改写 → 混合检索 → Rerank，返回拼接好的【参考资料N】文本
        String context = ragTool.searchKnowledge(userQuestion);

        // 2. 用检索到的资料生成回答
        String answer = chatClient.prompt()
                .system("根据以下参考资料回答用户问题。如果资料中没有答案，请明确告知。" +
                        "不要编造信息。\n\n参考资料：\n" + context)
                .user(userQuestion)
                .call()
                .content();

        // 3. 评估回答是否基于检索资料（防幻觉）
        EvaluationRequest evalRequest = new EvaluationRequest(userQuestion, answer);
        return relevancyEvaluator.evaluate(evalRequest);
    }
}
