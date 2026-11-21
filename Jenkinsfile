// Pipeline do backend (taxify-service): testes -> imagem Docker -> push no Docker Hub.
// Requer no Jenkins: plugins Pipeline, Git, Docker Pipeline e Credentials Binding;
// credencial "dockerhub" (usuário + access token do Docker Hub); agente com Docker.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Pular os testes (emergência)')
        booleanParam(name: 'PUSH_IMAGE', defaultValue: true, description: 'Enviar a imagem para o Docker Hub')
    }

    environment {
        IMAGE = 'thiagoocms/taxify-service'
        // Mesmo esquema de tag usado até agora: data + nº do build, ex. 2026.11.20.15
        TAG = "${new Date().format('yyyy.MM.dd')}.${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Testes') {
            when { expression { !params.SKIP_TESTS } }
            steps {
                script {
                    // Roda os testes num container com o mesmo JDK do Dockerfile; o cache
                    // do Maven fica num volume para não baixar tudo a cada build.
                    docker.image('eclipse-temurin:25-jdk').inside('-v taxify-m2:/root/.m2 -u root') {
                        sh 'chmod +x mvnw && ./mvnw -B test'
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Build da imagem') {
            steps {
                sh 'docker build -t $IMAGE:$TAG -t $IMAGE:latest .'
            }
        }

        stage('Push Docker Hub') {
            when {
                allOf {
                    expression { params.PUSH_IMAGE }
                    anyOf { branch 'master'; expression { env.BRANCH_NAME == null } }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub',
                        usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_TOKEN')]) {
                    sh '''
                        echo "$DOCKER_TOKEN" | docker login -u "$DOCKER_USER" --password-stdin
                        docker push $IMAGE:$TAG
                        docker push $IMAGE:latest
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Imagem publicada: ${IMAGE}:${TAG}"
        }
        always {
            sh 'docker logout || true'
        }
    }
}
