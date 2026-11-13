def call(Map config = [:]) {
    def workspacePath = config.workspacePath ?: 'workspace/resume-matcher-ci'
    
    stages {
        stage('Checkout Code') {
            steps {
                deleteDir()
                checkout scm
            }
        }

        stage('Normalize Line Endings') {
            steps {
                sh 'sed -i "s/\r$//" scripts/*.sh'
            }
        }

        stage('Ensure Scripts Executable') {
            steps {
                sh 'chmod +x scripts/*.sh'
            }
        }

        stage('Install Dependencies') {
            steps {
                sh 'npm install'
            }
        }

        stage('Run Tests') {
            steps {
                sh './scripts/ci-test.sh'
            }
        }

        stage('Setup Java 21') {
            steps {
                script {
                    def jdkHome = tool(
                        name: 'Java-21',
                        type: 'hudson.model.JDK'
                    )
                    env.CI_JAVA_HOME = jdkHome
                }

                sh '''
                    export JAVA_HOME="$CI_JAVA_HOME"
                    export PATH="$JAVA_HOME/bin:$PATH"

                    echo "Java configuration:"
                    java -version
                '''
            }
        }

        stage('SonarCloud Analysis') {
            steps {
                withCredentials([
                    string(credentialsId: 'SONAR_TOKEN', variable: 'SONAR_TOKEN'),
                    string(credentialsId: 'SONAR_HOST', variable: 'SONAR_HOST'),
                    string(credentialsId: 'SONAR_ORG', variable: 'SONAR_ORG'),
                    string(credentialsId: 'SONAR_PROJECT_KEY', variable: 'SONAR_PROJECT_KEY')
                ]) {
                    sh '''
                        export JAVA_HOME="$CI_JAVA_HOME"
                        export PATH="$JAVA_HOME/bin:$PATH"

                        rm -rf /var/jenkins_home/sonar-scanner-5.0.1.3006

                        ./scripts/ci-sonarcloud.sh
                    '''
                }
            }
        }

        stage('Download Trivy HTML Template') {
            steps {
                sh '''
                    curl --proto '=https' --tlsv1.2 -sSLf \
                    https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl \
                    -o html.tpl
                '''
            }
        }

        stage('Run Trivy FS Scan') {
            steps {
                sh '''
                    mkdir -p bin
                    if ! command -v trivy &> /dev/null; then
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b ./bin
                    fi

                    export PATH="$PWD/bin:$PATH"

                    trivy fs . \
                        --format template \
                        --template '@html.tpl' \
                        -o trivy-fs-report.html \
                        --ignore-unfixed \
                        --pkg-types os,library
                '''
            }
        }

        stage('Install & Run Gitleaks') {
            steps {
                withCredentials([
                    string(credentialsId: 'GITHUB_TOKEN', variable: 'GITHUB_TOKEN')
                ]) {
                    sh '''
                        mkdir -p bin
                        if ! command -v gitleaks &> /dev/null; then
                            curl -sSL https://github.com/gitleaks/gitleaks/releases/download/v8.18.2/gitleaks_8.18.2_linux_x64.tar.gz -o gitleaks.tar.gz
                            tar -xzf gitleaks.tar.gz -C ./bin gitleaks
                            rm gitleaks.tar.gz
                        fi

                        export PATH="$PWD/bin:$PATH"

                        gitleaks detect --source . --verbose || true
                    '''
                }
            }
        }

        stage('Security Checks') {
            steps {
                sh '''
                    export PATH="$PWD/bin:$PATH"
                    ./scripts/ci-security-checks.sh
                '''
            }
        }

        stage('Consolidate & Stash CI Reports') {
            steps {
                sh '''
                    mkdir -p reports/security

                    cp reports/security/security-report.html \
                        ./security-report.html 2>/dev/null || true

                    cp reports/sonar-summary.html \
                        ./sonar-summary.html 2>/dev/null || true

                    [ -f test-summary.html ] || \
                        echo "<html><body><h1>Test Summary Missing</h1></body></html>" \
                        > test-summary.html

                    [ -f sonar-summary.html ] || \
                        echo "<html><body><h1>Sonar Summary Missing</h1></body></html>" \
                        > sonar-summary.html

                    [ -f trivy-fs-report.html ] || \
                        echo "<html><body><h1>Trivy FS Scan Missing</h1></body></html>" \
                        > trivy-fs-report.html

                    [ -f trivy-img-report.html ] || \
                        echo "<html><body><h1>Trivy Image Scan Skipped (Docker Unavailable)</h1></body></html>" \
                        > trivy-img-report.html

                    [ -f security-report.html ] || \
                        echo "<html><body><h1>Security Report Missing</h1></body></html>" \
                        > security-report.html
                '''

                archiveArtifacts(
                    artifacts: '''
                        test-summary.html,
                        sonar-summary.html,
                        trivy-fs-report.html,
                        trivy-img-report.html,
                        security-report.html
                    ''',
                    allowEmptyArchive: true
                )

                stash(
                    name: 'ci-reports-premain',
                    includes: '''
                        test-summary.html,
                        sonar-summary.html,
                        trivy-fs-report.html,
                        trivy-img-report.html,
                        security-report.html
                    '''
                )
            }
        }
    }
}