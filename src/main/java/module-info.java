module top.spco.cashflow {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.apache.poi.ooxml;
    requires org.yaml.snakeyaml;

    opens top.spco.cashflow to javafx.fxml;
    opens top.spco.cashflow.data to javafx.fxml;

    opens top.spco.cashflow.ui to javafx.fxml;
    opens top.spco.cashflow.ui.record to javafx.fxml;
    opens top.spco.cashflow.ui.category to javafx.fxml;

    exports top.spco.cashflow;
    exports top.spco.cashflow.data;
}