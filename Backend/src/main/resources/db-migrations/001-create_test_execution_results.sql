-- Create test_execution_results table
CREATE TABLE IF NOT EXISTS test_execution_results (
    id UUID PRIMARY KEY NOT NULL,
    test_script_id UUID NOT NULL,
    project_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    execution_duration_ms BIGINT,
    scenario_results TEXT,
    assertion_results TEXT,
    performance_metrics TEXT,
    report_pdf_blob BYTEA,
    error_details TEXT,
    logs TEXT,
    selected_scenario_ids TEXT,
    executed_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_test_script FOREIGN KEY (test_script_id) REFERENCES test_scripts(id) ON DELETE CASCADE,
    CONSTRAINT fk_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_test_script_id ON test_execution_results(test_script_id);
CREATE INDEX IF NOT EXISTS idx_project_id ON test_execution_results(project_id);
CREATE INDEX IF NOT EXISTS idx_executed_at ON test_execution_results(executed_at);

-- Create execution_screenshots table for storing step screenshots
CREATE TABLE IF NOT EXISTS execution_screenshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    execution_result_id UUID NOT NULL,
    screenshot_blob BYTEA,
    CONSTRAINT fk_execution_result FOREIGN KEY (execution_result_id) REFERENCES test_execution_results(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_execution_screenshots_result_id ON execution_screenshots(execution_result_id);
