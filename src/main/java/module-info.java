module top.spco.cashflow {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.apache.poi.ooxml;
    requires org.yaml.snakeyaml;

    opens top.spco.cashflow to javafx.fxml;
    opens top.spco.cashflow.data to javafx.fxml;
    opens top.spco.cashflow.importer.config to javafx.fxml;
    opens top.spco.cashflow.importer.core to javafx.fxml;
    opens top.spco.cashflow.importer.wechat to javafx.fxml;
    opens top.spco.cashflow.model to javafx.fxml;
    opens top.spco.cashflow.service to javafx.fxml;
    opens top.spco.cashflow.ui to javafx.fxml;
    opens top.spco.cashflow.ui.category to javafx.fxml;
    opens top.spco.cashflow.ui.components to javafx.fxml;
    opens top.spco.cashflow.ui.importing to javafx.fxml;
    opens top.spco.cashflow.ui.record to javafx.fxml;
    opens top.spco.cashflow.ui.rules to javafx.fxml;
    opens top.spco.cashflow.util to javafx.fxml;
    opens top.spco.cashflow.viewmodel to javafx.fxml;

    exports top.spco.cashflow;
    exports top.spco.cashflow.data;
    exports top.spco.cashflow.importer.config;
    exports top.spco.cashflow.importer.core;
    exports top.spco.cashflow.importer.wechat;
    exports top.spco.cashflow.model;
    exports top.spco.cashflow.service;
    exports top.spco.cashflow.ui;
    exports top.spco.cashflow.ui.category;
    exports top.spco.cashflow.ui.components;
    exports top.spco.cashflow.ui.importing;
    exports top.spco.cashflow.ui.record;
    exports top.spco.cashflow.ui.rules;
    exports top.spco.cashflow.util;
    exports top.spco.cashflow.viewmodel;
}