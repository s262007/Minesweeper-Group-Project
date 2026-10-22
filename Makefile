# Run all tests
test:
	sbt test

# Run only the shared contract tests
contract:
	sbt "testOnly minesweeper.ContractTest"

# Remove build output only (never untracked source files)
clean:
	sbt clean
	rm -rf generated test_run_dir

.PHONY: test contract clean
