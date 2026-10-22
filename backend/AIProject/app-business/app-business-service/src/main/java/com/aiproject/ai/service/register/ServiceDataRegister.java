package com.aiproject.ai.service.register;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.aiproject.ai.service.bean.ApplicationsBean;
import com.aiproject.ai.service.bean.CapabilitiesBean;
import com.aiproject.ai.service.bean.DatasetsBean;
import com.aiproject.ai.service.bean.ModelsBean;
import com.aiproject.ai.service.holde.ApplicationsBeanHolder;
import com.aiproject.ai.service.holde.CapabilitiesBeanHolder;
import com.aiproject.ai.service.holde.DatasetsBeanHolder;
import com.aiproject.ai.service.holde.ModelsBeanHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceDataRegister implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    ApplicationsBeanHolder applicationsBeanHolder;

    @Autowired
    CapabilitiesBeanHolder capabilitiesBeanHolder;

    @Autowired
    DatasetsBeanHolder datasetsBeanHolder;

    @Autowired
    ModelsBeanHolder modelsBeanHolder;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        //能力广场数据初始化
        //initCapabilitiesBean();
        //应用构建数据初始化
        //initApplicationsBean();
        //数据集仓库数据初始化
        //initDatasetsBean();
        //模型仓库数据初始化
        //intiModelsBean();
    }

    private void intiModelsBean() {
        String json = "[{\"name\":\"Qwen3-VL-30B-A3B-Instruct\",\"category\":\"视觉多模态\",\"description\":\"采用混合专家（MoE）架构，总参数为300亿，仅需激活30亿参数即可在STEM、视觉问答、OCR、视频理解及智能体任务等多个领域展现出顶尖性能。\",\"size\":\"62.15GB\",\"provider\":\"通义千问\",\"tags\":\"视觉多模态, Mo态, MoE架构\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-VL-30B-A3B-Instruct\"}," +
                "{\"name\":\"Qwen3-Reranker-4B\",\"category\":\"重排序模型\",\"description\":\"通义千问推出的重排序（Reranker）模型，主要用于检索增强生成（RAG）等场景中对检索结果进行精准排序，提升信息检索的准确性。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"重排序模型, RAG\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Reranker-4B\"}," +
                "{\"name\":\"Qwen3-Reranker-0.6B\",\"category\":\"重排序模型\",\"description\":\"通义千问推出的重排序（Reranker）模型，主要用于检索增强生成（RAG）等场景中对检索结果进行精准排序，提升信息检索的准确性。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"重排序模型, RAG\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Reranker-0.6B\"}," +
                "{\"name\":\"Qwen3-Next-80B-A3B-Instruct\",\"category\":\"文本生成\",\"description\":\"通义千问系列中的大参数指令微调模型，属于MoE架构，适用于处理复杂的通用对话与专业任务。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"文本生成, MoE架构\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Next-80B-A3B-Instruct\"}," +
                "{\"name\":\"Qwen3-Embedding-4B\",\"category\":\"文本嵌入\",\"description\":\"参数量40亿，用于将文本转化为高维向量，广泛应用于语义搜索、文本聚类及相似度计算等任务。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"文本嵌入, 语义搜索\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Embedding-4B\"}," +
                "{\"name\":\"Qwen3-Embedding-0.6B\",\"category\":\"文本嵌入\",\"description\":\"参数量6亿，用于将文本转化为高维向量，广泛应用于语义搜索、文本聚类及相似度计算等任务。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"文本嵌入, 语义搜索\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Embedding-0.6B\"}," +
                "{\"name\":\"Qwen3-Coder-30B-A3B-Instruct\",\"category\":\"代码生成\",\"description\":\"专为代码生成、理解与优化设计的代码大模型，在数学、代码逻辑推理等方面具备出色的表现。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"代码生成, 代码理解\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-Coder-30B-A3B-Instruct\"}," +
                "{\"name\":\"Qwen3-8B\",\"category\":\"文本生成\",\"description\":\"通义千问推出的80亿参数规模的密集（Dense）模型，兼顾性能与部署成本，适用于多样化的通用场景。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"文本生成, Dense模型\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-8B\"}," +
                "{\"name\":\"Qwen3-32B\",\"category\":\"文本生成\",\"description\":\"通义千问推出的320亿参数规模的密集（Dense）模型，兼顾性能与部署成本，适用于多样化的通用场景。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"文本生成, Dense模型\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-32B\"}," +
                "{\"name\":\"PaddleOCR-VL-1.5\",\"category\":\"文字识别\",\"description\":\"由百度飞桨（PaddlePaddle）推出的多模态OCR（光学字符识别）模型，专注于各类场景下的高精度文字识别与提取。\",\"size\":\"\",\"provider\":\"百度\",\"tags\":\"文字识别, OCR\",\"downloadUrl\":\"http://127.0.0.1:8080/PaddleOCR-VL-1.5\"}," +
                "{\"name\":\"Qwen3-235B\",\"category\":\"视觉多模态理解\",\"description\":\"通义千问系列的旗舰级超大参数MoE模型，总参数高达2350亿（激活参数220亿），在代码、数学及通用能力基准测试中表现极具竞争力，是该系列的性能巅峰。\",\"size\":\"\",\"provider\":\"通义千问\",\"tags\":\"视觉多模态理解, MoE架构\",\"downloadUrl\":\"http://127.0.0.1:8080/Qwen3-235B-A22B\"}]";
        List<ModelsBean> list = JSONUtil.toList(json, ModelsBean.class);
        for (ModelsBean modelsBean : list) {
            ModelsBean bean = new ModelsBean();
            bean.setName(modelsBean.getName());
            bean = modelsBeanHolder.query(bean);
            if (null == bean) {
                modelsBeanHolder.installInternal(modelsBean);
            }
        }
    }

    private void initDatasetsBean() {
        String json = "[{\"name\":\"数据堂—176小时苏州方言手机语音采集语音数据\",\"type\":\"音频\",\"description\":\"176小时苏州方言手机语音采集数据，适合方言识别与语音模型训练。\",\"size\":\"12.8GB\",\"volume\":\"176H\",\"downloadUrl\":\"http://127.0.0.1:8081/DatatangBeijing/176Hours-SuzhouDialectSpeechDataByMobilePhone\"}," +
                "{\"name\":\"DISC-法律-SFT 数据集\",\"type\":\"文本\",\"description\":\"DISC系列法律领域微调数据集，适合法律问答与文本生成任务。\",\"size\":\"3.6GB\",\"volume\":\"120K\",\"downloadUrl\":\"http://127.0.0.1:8081/Robin021/DISC-Law-SFT\"}," +
                "{\"name\":\"数据堂—47,811句交互场景单句意图标注数据\",\"type\":\"文本\",\"description\":\"交互场景单句意图标注数据，适合意图识别与对话系统训练。\",\"size\":\"850MB\",\"volume\":\"47.8K\",\"downloadUrl\":\"http://127.0.0.1:8081/DatatangBeijing/47811Sentence-IntentionAnnotationDataInInteractiveScenes\"}," +
                "{\"name\":\"人民日报命名实体识别数据集(Tiny)\",\"type\":\"文本\",\"description\":\"人民日报命名实体识别数据集精简版，适合NER任务模型训练。\",\"size\":\"1.1MB\",\"volume\":\"50K\",\"downloadUrl\":\"http://127.0.0.1:8081/iic/people_daily_ner_1998_tiny\"}," +
                "{\"name\":\"CCKS 2021 地址解析数据集\",\"type\":\"文本\",\"description\":\"CCKS 2021地址解析评测数据集，适合地址实体识别与标准化。\",\"size\":\"650KB\",\"volume\":\"28K\",\"downloadUrl\":\"http://127.0.0.1:8081/iic/address-ner-ccks-2021\"}," +
                "{\"name\":\"wnut16 社交媒体命名实体识别数据集\",\"type\":\"文本\",\"description\":\"wnut16社交媒体英文命名实体识别数据集，适合非正式文本NER任务。\",\"size\":\"320KB\",\"volume\":\"15K\",\"downloadUrl\":\"http://127.0.0.1:8081/iic/wnut16_ner\"}," +
                "{\"name\":\"中文开源蒸馏满血R1数据集-110k-SFT\",\"type\":\"文本\",\"description\":\"基于满血DeepSeek-R1蒸馏数据集-110k-SFT版本，适合大模型微调训练。\",\"size\":\"8.2GB\",\"volume\":\"110K\",\"downloadUrl\":\"http://127.0.0.1:8081/licong/Chinese-DeepSeek-R1-Distill-data-110k-SFT\"}," +
                "{\"name\":\"中文Qwen3-235B蒸馏数据集-110k-SFT\",\"type\":\"文本\",\"description\":\"基于Qwen3-235B-2507蒸馏数据集-110k-SFT版本，适合大模型微调训练。\",\"size\":\"7.9GB\",\"volume\":\"110K\",\"downloadUrl\":\"http://127.0.0.1:8081/swift/Chinese-Qwen3-235B-2507-Distill-data-110k-SFT\"}]";
        List<DatasetsBean> list = JSONUtil.toList(json, DatasetsBean.class);
        for (DatasetsBean datasetsBean : list) {
            DatasetsBean bean = new DatasetsBean();
            bean.setName(datasetsBean.getName());
            bean = datasetsBeanHolder.query(bean);
            if (null == bean) {
                datasetsBeanHolder.installInternal(datasetsBean);
            }
        }
    }

    private void initCapabilitiesBean() {
        String json = "[{\"title\":\"易问智能助手\",\"description\":\"智能化知识管理与问答平台，支持动态接入各类大模型，用户可以便捷创建、管理及分享个人知识库。通过先进的语义理解与检索技术，系统能够基于知识库内容实现高效、精准的知识问答，大幅提升信息获取效率。\",\"label\":\"智能助手\",\"difyUrl\":\"http://127.0.0.1:83/home\"}," +
                "{\"title\":\"在线工具平台\",\"description\":\"集成研发常用工具软件，提供一站式在线开发、调试、测试等研发效能工具，助力开发者提升工作效率。\",\"label\":\"研发工具\",\"difyUrl\":\"http://127.0.0.1:55802\"}]";
        List<CapabilitiesBean> list = JSONUtil.toList(json, CapabilitiesBean.class);
        for (CapabilitiesBean capabilitiesBean : list) {
            CapabilitiesBean bean = new CapabilitiesBean();
            bean.setTitle(capabilitiesBean.getTitle());
            bean = capabilitiesBeanHolder.query(bean);
            if (null == bean) {
                capabilitiesBean.setRoleiids("[]");
                capabilitiesBean.setUnitsnos("[]");
                capabilitiesBeanHolder.installInternal(capabilitiesBean);
            }
        }
    }

    private void initApplicationsBean() {
        String json = "[{\"title\":\"智能工单分类与分派\",\"detail\":\"根据工单标题与描述自动识别业务类型、紧急程度，并分派到对应处理组，减少人工分拣成本。\",\"status\":\"运行中\",\"model\":\"通义千问 3-235B\",\"difyUrl\":\"\"}," +
                "{\"title\":\"合同条款风险审阅\",\"detail\":\"对上传的合同文本进行条款拆分，识别付款、违约、保密等风险点并给出修改建议。\",\"status\":\"运行中\",\"model\":\"通义千问 3-32B\",\"difyUrl\":\"\"}]";
        List<ApplicationsBean> list = JSONUtil.toList(json, ApplicationsBean.class);
        for (ApplicationsBean applicationsBean : list) {
            ApplicationsBean bean = new ApplicationsBean();
            bean.setTitle(applicationsBean.getTitle());
            bean = applicationsBeanHolder.query(bean);
            if (null == bean) {
                applicationsBean.setRoleiids("[]");
                applicationsBean.setUnitsnos("[]");
                applicationsBeanHolder.installInternal(applicationsBean);
            }
        }
    }

    @PreDestroy
    public synchronized void destroy() {

    }
}