module top.spco.cashflow {
    requires javafx.controls;
    requires javafx.fxml;

    opens top.spco.cashflow to javafx.fxml;
    opens top.spco.cashflow.data to javafx.fxml;

    exports top.spco.cashflow;
    exports top.spco.cashflow.data;
}